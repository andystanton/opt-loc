package otos.service

import java.sql.DriverManager

import akka.actor.Actor
import com.typesafe.config.ConfigFactory

case class IdRequest(id: Int)
case class NameRequest(name: String)
case class NearRequest(id: Int, params: NearParams)
case class NearParams(minRange: Option[Int], maxRange: Option[Int], minPopulation: Option[Long], maxPopulation: Option[Long])

class PostgresPlacesServiceActor extends Actor with PostgresPlacesService {
  val config = ConfigFactory.load("opt-loc.properties")

  implicit val jdbcUrl = config.getString("javax.persistence.jdbc.url")
  implicit val jdbcUser = config.getString("javax.persistence.jdbc.user")
  implicit val jdbcPassword = config.getString("javax.persistence.jdbc.password")

  def receive = {
    case IdRequest(id) =>
      sender ! findById(id)
    case NameRequest(name) =>
      sender ! findByName(name)
    case NearRequest(id, NearParams(minRange, maxRange, minPopulation, maxPopulation)) =>
      sender ! findNear(id, minRange, maxRange, minPopulation, maxPopulation)
  }
}

trait PostgresPlacesService {
  implicit def jdbcUrl: String
  implicit def jdbcUser: String
  implicit def jdbcPassword: String

  lazy val databaseConnection = {
    DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword)
  }

  def findById(id: Int): Location = {
    val query =
      s"""|SELECT
          |  id,
          |  name,
          |  ST_X(geom) as latitude,
          |  ST_Y(geom) as longitude,
          |  population
          |FROM
          |  places_gb
          |WHERE
          |  feature_class='P'
          |  AND id=$id
          |ORDER BY
          |  population DESC
          |LIMIT 1;
          |""".stripMargin

    val stmt = databaseConnection.prepareStatement(query)
    val resultSet = stmt.executeQuery
    resultSet.next

    Location(
      resultSet.getInt("id"),
      resultSet.getString("name"),
      Center(
        resultSet.getDouble("latitude"),
        resultSet.getDouble("longitude")
      ),
      resultSet.getLong("population")
    )
  }

  def findByName(locationSearch: String): List[Location] = {
    println(s"searching for: $locationSearch")
    val query =
      s"""|SELECT
          |  id,
          |  name,
          |  ST_X(geom) as latitude,
          |  ST_Y(geom) as longitude,
          |  population
          |FROM
          |  places_gb
          |WHERE
          |  feature_class='P'
          |  AND name ILIKE '%${locationSearch.toLowerCase}%'
          |ORDER BY
          |  population DESC;
          |""".stripMargin

    val stmt = databaseConnection.prepareStatement(query)
    val resultSet = stmt.executeQuery

    Iterator.continually(resultSet.next).takeWhile(_ == true).map { _ =>
      Location(
        resultSet.getInt("id"),
        resultSet.getString("name"),
        Center(
          resultSet.getDouble("latitude"),
          resultSet.getDouble("longitude")
        ),
        resultSet.getLong("population")
      )
    }.toList
  }

  def findNear(
    id: Int,
    minRange: Option[Int],
    maxRange: Option[Int],
    minPopulation: Option[Long],
    maxPopulation: Option[Long]
  ) : List[Location] = {
    val populationCeiling = 7000000000L

    val location = findById(id)
    val query =
      s"""|SELECT
          |  id,
          |  name,
          |  ST_X(geom) as latitude,
          |  ST_Y(geom) as longitude,
          |  population
          |FROM
          |  places_gb
          |WHERE
          |  id != '$id'
          |  AND feature_class='P'
          |  AND ST_Distance_Sphere(geom, ST_MakePoint(${location.center.latitude}, ${location.center.longitude})) <= ${maxRange.get}
          |  AND ST_Distance_Sphere(geom, ST_MakePoint(${location.center.latitude}, ${location.center.longitude})) >= ${minRange.get}
          |  AND population BETWEEN ${minPopulation.get} AND ${maxPopulation.getOrElse(populationCeiling)}
          |ORDER BY
          |  name ASC
          |""".stripMargin
    println(query)

    val stmt = databaseConnection.prepareStatement(query)
    val resultSet = stmt.executeQuery

    Iterator.continually(resultSet.next).takeWhile(_ == true).map { _ =>
        Location(
          resultSet.getInt("id"),
          resultSet.getString("name"),
          Center(
            resultSet.getDouble("latitude"),
            resultSet.getDouble("longitude")
          ),
        resultSet.getLong("population")
        )
    }.toList
  }
}
