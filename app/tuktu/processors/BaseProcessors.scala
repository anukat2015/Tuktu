package tuktu.processors

import java.io._
import java.lang.reflect.Method
import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import akka.actor._
import akka.pattern.ask
import au.com.bytecode.opencsv.CSVWriter
import groovy.util.Eval
import play.api.Play.current
import play.api.libs.concurrent.Akka
import play.api.libs.iteratee.Concurrent
import play.api.libs.iteratee.Enumeratee
import play.api.libs.iteratee.Iteratee
import play.api.libs.json._
import play.api.libs.json.Json.toJsFieldJsValueWrapper
import tuktu.api._
import java.text.SimpleDateFormat

/**
 * Filters specific fields from the data tuple
 */
class FieldFilterProcessor(resultName: String) extends BaseProcessor(resultName) {
    var fieldList = List[JsObject]()
    
    override def initialize(config: JsObject) = {
        // Find out which fields we should extract
        fieldList = (config \ "fields").as[List[JsObject]]
    }
    
    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.mapM(data => {
        Future {new DataPacket(for (datum <- data.data) yield {
            val newData = (for {
                    fieldItem <- fieldList
                    default = (fieldItem \ "default").asOpt[JsValue]
                    fields = (fieldItem \ "path").as[List[String]]
                    fieldName = (fieldItem \ "result").as[String]
                    field = fields.head
                    if (fields.size > 0 && datum.contains(field))
            } yield {
                // See what to do
                if (datum(field).isInstanceOf[JsValue])
                    fieldName -> tuktu.utils.util.jsonParser(datum(field).asInstanceOf[JsValue], fields.drop(1), default)
                else
                    fieldName -> tuktu.utils.util.fieldParser(datum, fields, default)
            }).toMap
            
            newData
        })}
    })
}

/**
 * Adds a running count integer to data coming in
 */
class RunningCountProcessor(resultName: String) extends BaseProcessor(resultName) {
    var cnt = 0
    var perBlock = false
    var stepSize = 1
    
    override def initialize(config: JsObject) = {
        cnt = (config \ "start_at").asOpt[Int].getOrElse(0)
        perBlock = (config \ "per_block").asOpt[Boolean].getOrElse(false)
        stepSize = (config \ "step_size").asOpt[Int].getOrElse(1)
    }
    
    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.mapM(data => Future {
        if (perBlock) {
            val res = new DataPacket(data.data.map(datum => datum + (resultName -> cnt)))
            cnt += stepSize
            res
        }
        else {
            new DataPacket(data.data.map(datum => {
                val r = datum + (resultName -> cnt)
                cnt += stepSize
                r
            }))
        }
    })
}

/**
 * Replaces one string for another (could be regex)
 */
class ReplaceProcessor(resultName: String) extends BaseProcessor(resultName) {
    var field = ""
    var sources = List[String]()
    var targets = List[String]()
    
    def replaceHelper(accum: String, offset: Int): String = {
        if (offset >= sources.size) accum
        else {
            // Replace in the accumulator and advance
            replaceHelper(accum.replaceAll(sources(offset), targets(offset)), offset + 1)
        }
    }
    
    override def initialize(config: JsObject) = {
        field = (config \ "field").as[String]
        sources = (config \ "sources").as[List[String]]
        targets = (config \ "targets").as[List[String]]
    }
    
    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.mapM(data => Future {
        new DataPacket(data.data.map(datum => datum + (field -> {
            // Get field value to replace
            val value = datum(field).toString
            
            // Replace
            replaceHelper(value, 0)
        })))
    })
}

/**
 * Gets a JSON Object and fetches a single field to put it as top-level citizen of the data
 */
class JsonFetcherProcessor(resultName: String) extends BaseProcessor(resultName) {
    var fieldList = List[JsObject]()
    
    override def initialize(config: JsObject) = {
        // Find out which fields we should extract
        fieldList = (config \ "fields").as[List[JsObject]]
    }
    
    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.mapM(data => {
        Future {new DataPacket(for (datum <- data.data) yield {
            val newData = (for {
                    fieldItem <- fieldList
                    default = (fieldItem \ "default").asOpt[JsValue]
                    fields = (fieldItem \ "path").as[List[String]]
                    fieldName = (fieldItem \ "result").as[String]
                    field = fields.head
                    if (fields.size > 0 && datum.contains(field))
            } yield {
                // See what to do
                if (datum(field).isInstanceOf[JsValue])
                    fieldName -> tuktu.utils.util.jsonParser(datum(field).asInstanceOf[JsValue], fields.drop(1), default)
                else
                    fieldName -> tuktu.utils.util.fieldParser(datum, fields, default)
            }).toMap
            
            datum ++ newData
        })}
    })
}

/**
 * Renames a single field
 */
class FieldRenameProcessor(resultName: String) extends BaseProcessor(resultName) {
    var fieldList = List[JsObject]()
    
    override def initialize(config: JsObject) = {
        fieldList = (config \ "fields").as[List[JsObject]]
    }
    
    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.mapM(data => {
        Future {new DataPacket(for (datum <- data.data) yield {
	        var mutableDatum = collection.mutable.Map(datum.toSeq: _*) 
	        for {
	                field <- fieldList
                    
                    fields = (field \ "path").as[List[String]]
                    fieldName = (field \ "result").as[String]
                    f = fields.head
                    if (f.size > 0 && datum.contains(f))
	        } {
                // See what to do
                val newValue = {
                    if (datum(f).isInstanceOf[JsValue])
                        fieldName -> tuktu.utils.util.jsonParser(datum(f).asInstanceOf[JsValue], fields.drop(1), null)
                    else
                        fieldName -> tuktu.utils.util.fieldParser(datum, fields, null)
                }

	            // Replace
	            mutableDatum = mutableDatum - f + newValue
	        }
	        
	        mutableDatum.toMap
        })}
    })
}

/**
 * Includes or excludes specific datapackets
 */
class InclusionProcessor(resultName: String) extends BaseProcessor(resultName) {
    var expression: String = null
    var expressionType: String = null
    var andOr: String = null
    
    override def initialize(config: JsObject) = {
        // Get the groovy expression that determines whether to include or exclude
        expression = (config \ "expression").as[String]
        // See if this is a simple or groovy expression
        expressionType = (config \ "type").as[String]
        // Set and/or
        andOr = (config \ "and_or").asOpt[String] match {
            case Some("or") => "or"
            case _ => "and"
        }
    }
    
    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.mapM((data: DataPacket) => {
        Future {new DataPacket(for {
                datum <- data.data
                // See if we need to include this
                include = expressionType match {
                    case "groovy" => {
                        // Replace expression with values
                    	val replacedExpression = tuktu.api.utils.evaluateTuktuString(expression, datum)
                    	
	                    try {
	                        Eval.me(replacedExpression).asInstanceOf[Boolean]
	                    } catch {
	                        case _: Throwable => true
	                    }
                    }
                    case "negate" => {
                        // This is a comma-separated list of field=val statements
                        val matches = expression.split(",").map(m => m.trim)
                        val evals = for (m <- matches) yield {
                            val split = m.split("=").map(s => s.trim)
                            // Get field and value and see if they match
                            datum(split(0)) == split(1)
                        }
                        // See if its and/or
                        if (andOr == "or") !evals.exists(elem => elem)
                        else evals.exists(elem => !elem)
                    }
                    case _ => {
                        // This is a comma-separated list of field=val statements
                        val matches = expression.split(",").map(m => m.trim)
                        val evals = (for (m <- matches) yield {
                            val split = m.split("=").map(s => s.trim)
                            // Get field and value and see if they match
                            datum(split(0)) == split(1)
                        }).toList
                        // See if its and/or
                        if (andOr == "or") evals.exists(elem => elem)
                        else !evals.exists{elem => !elem}
                    }
                }
                if (include)
        } yield {
            datum
        })}
    }) compose Enumeratee.filter((data: DataPacket) => {
        data.data.size > 0
    })
}

/**
 * Adds a field with a constant (static) value
 */
class FieldConstantAdderProcessor(resultName: String) extends BaseProcessor(resultName) {
    var value = ""
    var isNumeric = false
    
    override def initialize(config: JsObject) = {
        value = (config \ "value").as[String]
        isNumeric = (config \ "is_numeric").asOpt[Boolean].getOrElse(false)
    }
    
    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.mapM(data => {
        Future {new DataPacket(for (datum <- data.data) yield {
            if(!isNumeric)
	            datum + (resultName -> value.toString)
            else
                datum + (resultName -> value.toLong)
        })}
    })
}

/**
 * Dumps the data to console
 */
class ConsoleWriterProcessor(resultName: String) extends BaseProcessor(resultName) {
    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.mapM(data => {
        for (datum <- data.data) {
            prettify(datum)
            println
        }

        Future {data}
    })
    
    def prettify(map: Map[String, Any], level: Int = 0): Unit = {
        for (tuple <- map) {
            print ("   " * level + tuple._1 + " : ")
            if(tuple._2.isInstanceOf[Map[_,_]]) {
                println  
                prettify(tuple._2.asInstanceOf[Map[String, Any]], level+1) 
            } else {
                print (tuple._2 + "\r\n")
            }
        }
    }
}

/**
 * Implodes an array into a string
 */
class ImploderProcessor(resultName: String) extends BaseProcessor(resultName) {
    var fieldList = List[JsObject]()
    override def initialize(config: JsObject) = {
        fieldList = (config \ "fields").as[List[JsObject]]
    }
    
    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.mapM(data => {
        Future {new DataPacket(for (datum <- data.data) yield {
            // Find out which fields we should extract
	        var mutableDatum = collection.mutable.Map(datum.toSeq: _*) 
	        for (fieldObject <- fieldList) {
	            // Get fields
	            val fields = (fieldObject \ "path").as[List[String]]
	            val sep = (fieldObject \ "separator").as[String]
	            // Get field name
	            val field = fields.head
	            // Get the actual value
	            val value = {
	                if (datum(field).isInstanceOf[JsValue])
	                    tuktu.utils.util.jsonParser(datum(field).asInstanceOf[JsValue], fields.drop(1), None).as[List[String]]
	                else {
	                	val someVal = tuktu.utils.util.fieldParser(datum, fields, None)
	                	if (someVal.isInstanceOf[Array[String]]) someVal.asInstanceOf[Array[String]].toList
	                	else if (someVal.isInstanceOf[Seq[String]]) someVal.asInstanceOf[Seq[String]].toList
	                	else someVal.asInstanceOf[List[String]]
	                }
	            }
	            // Replace
	            mutableDatum += field -> value.mkString(sep)
	        }
	        mutableDatum.toMap
        })}
    })
}

/**
 * Implodes an array  of JSON object-fields into a string
 */
class JsObjectImploderProcessor(resultName: String) extends BaseProcessor(resultName) {
    var fieldList = List[JsObject]()
    
    override def initialize(config: JsObject) = {
        fieldList = (config \ "fields").as[List[JsObject]]
    }
    
    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.mapM(data => {
        Future {new DataPacket(for (datum <- data.data) yield {
            // Find out which fields we should extract
	        var mutableDatum = collection.mutable.Map(datum.toSeq: _*) 
	        for (fieldObject <- fieldList) {
	            // Get fields
	            val fields = (fieldObject \ "path").as[List[String]]
	            val subpath = (fieldObject \ "subpath").as[List[String]]
	            val sep = (fieldObject \ "separator").as[String]
	            // Get field name
	            val field = fields.head
	            // Get the actual value
	            val values = {
	                if (datum(field).isInstanceOf[JsArray]) tuktu.utils.util.jsonParser(datum(field).asInstanceOf[JsValue], fields.drop(1), None).as[List[JsObject]]
	                else List[JsObject]()
	            }
	            // Now iterate over the objects
	            val gluedValue = values.map(value => {
	                tuktu.utils.util.JsonStringToNormalString(tuktu.utils.util.jsonParser(value, subpath, None).as[JsString])
	            }).mkString(sep)
	            // Replace
	            mutableDatum += field -> gluedValue
	        }
	        mutableDatum.toMap
        })}
    })
}

/**
 * Flattens a map object
 */
class FlattenerProcessor(resultName: String) extends BaseProcessor(resultName) {
    def recursiveFlattener(mapping: Map[String, Any], currentKey: String, sep: String): Map[String, Any] = {
        // Get the values of the map
        (for (mapElem <- mapping.toList) yield {
            val key = mapElem._1
            val value = mapElem._2
            
            value.isInstanceOf[Map[String, Any]] match {
                case true => {
		            // Get the sub fields recursively
                    recursiveFlattener(value.asInstanceOf[Map[String, Any]], currentKey + sep + key, sep)
		        }
		        case false => {
		            Map(currentKey + sep + key -> value)
		        }
            }
        }).toList.foldLeft(Map[String, Any]())(_ ++ _)
    }
    
    var fieldList = List[String]()
    var separator = ""
    
    override def initialize(config: JsObject) = {
        // Get the field to flatten
        fieldList = (config \ "fields").as[List[String]]
        separator = (config \ "separator").as[String]
    }
    
    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.mapM(data => {
        Future {new DataPacket(for (datum <- data.data) yield {
            // Set up mutable datum
            var mutableDatum = collection.mutable.Map(datum.toSeq: _*)
            
            // Find out which fields we should extract
	        for (fieldName <- fieldList) {
                // Remove the fields we need to extract
                mutableDatum -= fieldName
                
	            // Get the value
	            val value = {
	                try {
	                    recursiveFlattener(datum(fieldName).asInstanceOf[Map[String, Any]], fieldName, separator)
	                } catch {
	                    case e: Exception => {
	                        e.printStackTrace()
	                        Map[String, Any]()
	                    }
	                }
	            }
	            
	            // Replace
	            mutableDatum ++= value
	        }
	        mutableDatum.toMap
        })}
    })
}

/**
 * Takes a (JSON) sequence object and returns packets for each of the values in it
 */
class SequenceExploderProcessor(resultName: String) extends BaseProcessor(resultName) {
    var field = ""
    var ignoreEmpty = true
    
    override def initialize(config: JsObject) = {
        field = (config \ "field").as[String]
        ignoreEmpty = (config \ "ignore_empty").asOpt[Boolean].getOrElse(true)
    }
    
    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.mapM((data: DataPacket) => Future {
        new DataPacket((for (datum <- data.data) yield {
            // Get the field and explode it
            val values = datum(field).asInstanceOf[Seq[Any]]
            
            for (value <- values) yield datum + (field -> value)
        }).flatten)
    }) compose Enumeratee.filter((data: DataPacket) => !data.data.isEmpty)
}

/**
 * Splits a string up into a list of values based on a separator
 */
class StringSplitterProcessor(resultName: String) extends BaseProcessor(resultName) {
    var field = ""
    var separator = ""
    var overwrite = false
    
    override def initialize(config: JsObject) = {
        field = (config \ "field").as[String]
        separator = (config \ "separator").as[String]
        overwrite = (config \ "overwrite").asOpt[Boolean].getOrElse(false)
    }
    
    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.mapM(data => Future {
        new DataPacket(for (datum <- data.data) yield {
            // Get the field and explode it
            val values = datum(field).toString.split(separator).toList
            
            datum + {
                if (overwrite) field -> values else resultName -> values
            }
        })
    })
}

/**
 * Assumes the data is a List[Map[_]] and gets specific fields from the map to remain in the list
 */
class ListMapFlattenerProcessor(resultName: String) extends BaseProcessor(resultName) {
    var listField = ""
    var mapField = ""
    var ignoreEmpty = true
    var overwrite = true
    
    override def initialize(config: JsObject) = {
        listField = (config \ "list_field").as[String]
        mapField = (config \ "map_field").as[String]
        ignoreEmpty = (config \ "ignore_empty").asOpt[Boolean].getOrElse(true)
        overwrite = (config \ "overwrite").asOpt[Boolean].getOrElse(true)
    }
    
    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.mapM((data: DataPacket) => Future {
        new DataPacket(for (datum <- data.data) yield {
            // Get the list field's value
            val listValue = datum(listField).asInstanceOf[List[Map[String, Any]]]
            
            // Get the actual fields of the maps iteratively
            val newList = listValue.map(listItem => {
                // Get map field
                listItem(mapField)
            })
            
            // Return new list rather than old
            if (overwrite)
                datum + (listField -> newList)
            else
                datum + (resultName -> newList)
        })
    }) compose Enumeratee.filter((data: DataPacket) => !data.data.isEmpty)
}