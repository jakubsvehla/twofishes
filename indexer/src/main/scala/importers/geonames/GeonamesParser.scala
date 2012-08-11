// Copyright 2012 Foursquare Labs Inc. All Rights Reserved.
package com.foursquare.twofishes.importers.geonames

import com.foursquare.twofishes._
import com.foursquare.twofishes.Helpers._
import com.foursquare.twofishes.Implicits._
import java.io.File

object GeonamesParser {
  val geonameIdNamespace = "geonameid"
  val geonameAdminIdNamespace = "gadminid"

  var config: GeonamesImporterConfig = null

  def main(args: Array[String]) {
    val store = new MongoGeocodeStorageService()
    val parser = new GeonamesParser(store)
    config = new GeonamesImporterConfig(args)

    if (!config.parseWorld) {
      parser.parseAdminFile(
        "data/downloaded/%s.txt".format(config.parseCountry))

      if (config.importPostalCodes) {
        parser.parsePostalCodeFile(
        "data/downloaded/zip/%s.txt".format(config.parseCountry),
        true)
      }
    } else {
      parser.parseAdminFile(
        "data/downloaded/allCountries.txt")
      if (config.importPostalCodes) {
        parser.parsePostalCodeFile(
          "data/downloaded/zip/allCountries.txt", false)
      }
    }

    new File("data/supplemental/").listFiles.foreach(f => {
      println("parsing supplemental file: %s".format(f))
      parser.parseAdminFile(f.toString, allowBuildings=true)
    })

    if (config.importAlternateNames) {
      parser.parseAlternateNamesFile(
        "data/downloaded/alternateNames.txt")
    }

    parser.parsePreferredNames()

    if (config.importBoundingBoxes) {
      new File(config.boundingBoxDirectory).listFiles.toList.sorted.foreach(f => {
        println("parsing bounding box file: %s".format(f))
        new BoundingBoxTsvImporter(store).parse(f.toString)
      })
    }

    new OutputHFile(config.hfileBasePath).process()

  }
}

import GeonamesParser._

class GeonamesParser(store: GeocodeStorageWriteService) {
  object logger {
    def error(s: String) { println("**ERROR** " + s)}
    def info(s: String) { println(s)}
  }

  // token -> alt tokens
  val rewriteTable = new TsvHelperFileParser("data/custom/rewrites.txt",
    "data/private/rewrites.txt")
  // tokenlist
  val deletesList: List[String] = scala.io.Source.fromFile(new File("data/custom/deletes.txt")).getLines.map(NameNormalizer.normalize).toList
  // geonameid -> boost value
  val boostTable = new TsvHelperFileParser("data/custom/boosts.txt")
  // geonameid -> alias
  val aliasTable = new TsvHelperFileParser("data/custom/aliases.txt",
    "data/private/aliases.txt")


  val helperTables = List(rewriteTable, boostTable, aliasTable)

  def logUnusedHelperEntries {
    helperTables.foreach(_.logUnused)
  }

  def doRewrites(names: List[String]): List[String] = {
    val nameSet = new scala.collection.mutable.HashSet() ++ names.toSet
    rewriteTable.gidMap.foreach({case(from, toList) => {
      nameSet.foreach(name => {
        toList.values.foreach(to => {
          nameSet += name.replace(from, to)
        })
      })
    }})
    nameSet.toList
  }

  def doDeletes(names: List[String]) = {
    val nameSet = new scala.collection.mutable.HashSet() ++ names.toSet
    // val newNameSet = new scala.collection.mutable.HashSet() ++ names.toSet
    deletesList.foreach(delete => {
      nameSet.foreach(name => {
        nameSet += name.replace(delete, "").split(" ").filterNot(_.isEmpty).mkString(" ")
      })
    })
    nameSet.toList.filterNot(_.isEmpty)
  }

  def parseFeature(feature: GeonamesFeature): GeocodeRecord = {
    // Build ids
    val adminId = feature.adminId.map(id => StoredFeatureId(geonameAdminIdNamespace, id))
    val geonameId = feature.geonameid.map(id => {
      if (id.contains(":")) {
        val parts = id.split(":")
        StoredFeatureId(parts(0), parts(1))
      } else {
        StoredFeatureId(geonameIdNamespace, id)
      }
    })

    val ids: List[StoredFeatureId] = List(adminId, geonameId).flatMap(a => a)

    // Build names
    val aliases: List[String] = feature.geonameid.toList.flatMap(gid => {
      aliasTable.get(gid)
    })

    val allNames = feature.allNames ++ aliases
    val normalizedNames = allNames.map(n => NameNormalizer.normalize(n))
    val deaccentedNames = normalizedNames.map(n => NameNormalizer.deaccent(n))
    val names = doDeletes(doRewrites((normalizedNames ++ deaccentedNames).toSet.toList.filterNot(_.isEmpty)))

    // Build parents
    val extraParents: List[String] = feature.extraColumns.get("parents").toList.flatMap(_.split(",").toList)
    val parents: List[String] = feature.parents.map(p => StoredFeatureId(geonameAdminIdNamespace, p))
    val allParents = parents ++ extraParents

    val boost: Option[Int] = feature.geonameid.flatMap(gid => {
      boostTable.get(gid).headOption.flatMap(boost =>
        tryo { boost.toInt }
      )
    })

    val bbox = feature.extraColumns.get("bbox").flatMap(bboxStr => {
      // west, south, east, north
      val parts = bboxStr.split(",").map(_.trim)
      parts.toList match {
        case w :: s :: e :: n :: Nil => {
          Some(BoundingBox(Point(n.toDouble, e.toDouble), Point(s.toDouble, w.toDouble)))
        }
        case _ => {
          logger.error("malformed bbox: " + bboxStr)
          None
        }
      }
    })

    val record = GeocodeRecord(
      ids = ids,
      names = names,
      cc = feature.countryCode,
      _woeType = feature.featureClass.woeType.getValue,
      lat = feature.latitude,
      lng = feature.longitude,
      parents = allParents,
      population = feature.population,
      displayNames = List(DisplayName("en", feature.name, false)),
      boost = boost,
      boundingbox = bbox
    )

    store.insert(record)

    record
  }

  def parseAdminFile(filename: String, allowBuildings: Boolean = false) {
    parseFromFile(filename, (index: Int, line: String) => 
      GeonamesFeature.parseFromAdminLine(index, line), "features", allowBuildings)
  }

  def parsePostalCodeFile(filename: String, countryFile: Boolean) {
    parseFromFile(filename, (index: Int, line: String) => 
      GeonamesFeature.parseFromPostalCodeLine(index, line), "postal codes")
  }

  private def parseFromFile(filename: String,
    lineProcessor: (Int, String) => Option[GeonamesFeature],
    typeName: String,
    allowBuildings: Boolean = false) {
    val lines = scala.io.Source.fromFile(new File(filename), "UTF-8").getLines
    lines.zipWithIndex.foreach({case (line, index) => {
      if (index % 10000 == 0) {
        logger.info("imported %d %s so far".format(index, typeName))
      }
      val feature = lineProcessor(index, line)
      feature.foreach(f => {
        if (!f.featureClass.isBuilding || config.shouldParseBuildings || allowBuildings) {
          parseFeature(f)
        }
      })
    }})
  }

  def parseAlternateNamesFile(filename: String) {
    val lines = scala.io.Source.fromFile(new File(filename)).getLines
    lines.zipWithIndex.foreach({case (line, index) => {
      if (index % 10000 == 0) {
        logger.info("imported %d alternateNames so far".format(index))
      }

      val parts = line.split("\t").toList
      if (parts.size < 4) {
          logger.error("line %d didn't have 5 parts: %s -- %s".format(index, line, parts.mkString(",")))
        } else {
          val geonameid = parts(1)
          val lang = parts(2)
          val altName = parts(3)
          val isPrefName = parts.lift(4).exists(_ == "1")
          val isShortName = parts.lift(5).exists(_ == "1")

          if (lang != "post") {
            val name = DisplayName(lang, altName, isPrefName)
            store.addNameToRecord(name, StoredFeatureId(geonameIdNamespace, geonameid))
          }
        }
    }})
  }

  def parsePreferredNames() {
    // geonameid -> lang|prefName
    val filename = "data/custom/names.txt"
    val lines = scala.io.Source.fromFile(new File(filename)).getLines

    lines.foreach(line => {
      val parts = line.split("\t").toList
      for {
        gid <- parts.lift(0)
        lang <- parts.lift(1).flatMap(_.split("\\|").lift(0))
        name <- parts.lift(1).flatMap(_.split("\\|").lift(1))
      } {
        val records = store.getById(StoredFeatureId(geonameIdNamespace, gid)).toList
        records match {
          case Nil => logger.error("no match for id %s".format(gid))
          case record :: Nil => {
            var foundName = false
            val modifiedNames: List[DisplayName] = record.displayNames.map(dn => {
              if (dn.lang == lang) {
                if (dn.name == name) {
                  foundName = true
                  DisplayName(dn.lang, dn.name, true)
                } else {
                  DisplayName(dn.lang, dn.name, false)
                }
              } else {
                dn
              }
            })

            val newNames = modifiedNames ++ (
              if (foundName) { Nil } else { 
                List(DisplayName(lang, name, true))
              }
            )            

            store.setRecordNames(StoredFeatureId(geonameIdNamespace, gid), newNames)
          }
        }
      }
    })
  }
}