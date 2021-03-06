package tuktu.nosql.processors.mongodb

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.TimeoutException
import scala.concurrent.duration.DurationInt
import play.api.Play.current
import play.api.cache.Cache
import play.api.libs.iteratee.Enumeratee
import play.api.libs.json.JsObject
import play.api.libs.json.Json
import play.modules.reactivemongo.json._
import play.modules.reactivemongo.json.collection._
import reactivemongo.core.nodeset.Authenticate
import tuktu.api.BaseProcessor
import tuktu.api.DataPacket
import tuktu.nosql.util._
import scala.collection.immutable.SortedSet
import scala.util.Failure
import scala.util.Success
import reactivemongo.api.MongoConnection
import akka.util.Timeout
import tuktu.api.utils

/**
 * Removes data from MongoDB
 */
class MongoDBRemoveProcessor(resultName: String) extends BaseProcessor(resultName) {
    implicit val timeout = Timeout(Cache.getAs[Int]("timeout").getOrElse(5) seconds)
    var conn: MongoConnection = _
    var nodes: List[String] = _
    
    var db: String = _
    var collection: String = _
    
    var query: String = _
    var justOne: Boolean = _
    var waitForCompletion: Boolean = _
    
    override def initialize(config: JsObject) {
        // Get hosts
        nodes = (config \ "hosts").as[List[String]]
        // Get connection properties
        val opts = (config \ "mongo_options").asOpt[JsObject]
        val mongoOptions = MongoPool.parseMongoOptions(opts)
        // Get credentials
        val authentication = (config \ "auth").asOpt[JsObject]
        val auth = authentication match {
            case None => None
            case Some(a) => Some(Authenticate(
                    (a \ "db").as[String],
                    (a \ "user").as[String],
                    (a \ "password").as[String]
            ))
        }
        
        // DB and collection
        db = (config \ "db").as[String]
        collection = (config \ "collection").as[String]

        // Get query and filter
        query = (config \ "query").as[String]

        // Only delete maximum of one item?
        justOne = (config \ "just_one").asOpt[Boolean].getOrElse(false)
        
        // Wait for deletion to complete?
        waitForCompletion = (config \ "wait_for_completion").asOpt[Boolean].getOrElse(false)
        
        // Get the connection
        val fConnection = MongoPool.getConnection(nodes, mongoOptions, auth)
        conn = Await.result(fConnection, timeout.duration)
    }

    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.mapM((data: DataPacket) => {
        // Remove data from MongoDB
        val jsons = (for (datum <- data.data) yield {
            (
                    utils.evaluateTuktuString(db, datum),
                    utils.evaluateTuktuString(collection, datum),
                    Json.parse(stringHandler.evaluateString(query, datum, "\"", ""))
            )
        }).toList.groupBy(_._1).map(elem => elem._1 -> elem._2.groupBy(_._2))
        
        // Execute per DB/Collection pair
        val resultFut = Future.sequence(for {
            (dbEval, collectionMap) <- jsons
            (collEval, queries) <- collectionMap
        } yield {
            val fCollection = MongoPool.getCollection(conn, dbEval, collEval)
            fCollection.flatMap(coll => coll.remove[JsObject](Json.obj("$or" -> queries.map(_._3).distinct), firstMatchOnly = justOne))
        })
        
        // Continue directly or wait?
        if (waitForCompletion) resultFut.map { _ => data }
        else Future { data }
    }) compose Enumeratee.onEOF(() => MongoPool.releaseConnection(nodes, conn))
}