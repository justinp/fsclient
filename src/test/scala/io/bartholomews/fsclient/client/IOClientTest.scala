package io.bartholomews.fsclient.client

import cats.effect.IO
import fs2.Pipe
import io.bartholomews.fsclient.codecs.FsJsonResponsePipe
import io.bartholomews.fsclient.entities.OAuthVersion.OAuthV1
import io.bartholomews.fsclient.mocks.server.{OAuthServer, WiremockServer}
import io.bartholomews.fsclient.requests._
import io.circe.generic.extras.Configuration
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json}
import org.http4s.Status
import org.scalatest.tagobjects.Slow
import org.scalatest.wordspec.AnyWordSpec

class IOClientTest extends AnyWordSpec with IOClientMatchers with WiremockServer with OAuthServer {

  import io.bartholomews.fsclient.implicits._

  "A valid simple client with no OAuth" when {

    val client: FsClient[IO, OAuthV1] = validSimpleClient()

    def validPlainTextResponseGetEndpoint[R]: FsSimpleRequest.Get[Nothing, String, R] =
      getPlainTextEndpoint[R](okPlainTextResponse)

    def validPlainTextResponsePostEndpoint[B, R](body: B): FsSimpleRequest[B, String, R] =
      postPlainTextEndpoint(okPlainTextResponse, body)

    def timeoutResponseGetEndpoint[R]: FsSimpleRequest.Get[Nothing, Json, R] =
      getJsonEndpoint(timeoutResponse)

    def timeoutResponsePostEndpoint[B, R](body: B): FsSimpleRequest[B, Json, R] =
      postJsonEndpoint(timeoutResponse, body)

    case class MyRequestBody(a: String, b: List[Int])
    object MyRequestBody extends FsJsonResponsePipe[MyRequestBody] {
      import io.circe.generic.semiauto._
      implicit val encoder: Encoder[MyRequestBody] = deriveEncoder
      implicit val decoder: Decoder[MyRequestBody] = deriveDecoder
    }

    val requestBody: MyRequestBody = MyRequestBody("A", List(1, 2, 3))

    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

    "calling a GET endpoint with Json response" when {

      "response is 200" should {

        def validResponseGetEndpoint[R]: FsSimpleRequest.Get[Nothing, Json, R] =
          getJsonEndpoint(okJsonResponse)

        "retrieve the json with Status Ok and entity" in {
          assertRight(Map("message" -> "this is a json response").asJson) {
            validResponseGetEndpoint[Json].runWith(client)
          }
        }

        "retrieve the decoded json with Status Ok and entity" in {
          case class ValidEntity(message: String)
          object ValidEntity extends FsJsonResponsePipe[ValidEntity] {
            implicit val decoder: Decoder[ValidEntity] = io.circe.generic.semiauto.deriveDecoder
          }
          assertRight(ValidEntity("this is a json response")) {
            val r: FsSimpleRequest.Get[Nothing, Json, ValidEntity] = validResponseGetEndpoint[ValidEntity]
            r.runWith(client)
          }
        }

        "respond with error if the response json is unexpected" in {
          case class InvalidEntity(something: Boolean)
          object InvalidEntity extends FsJsonResponsePipe[InvalidEntity] {
            implicit val conf: Configuration = io.circe.generic.extras.defaults.defaultGenericConfiguration
            implicit val dec: Decoder[InvalidEntity] = io.circe.generic.extras.semiauto.deriveConfiguredDecoder
          }
          assertDecodingFailure {
            validResponseGetEndpoint[InvalidEntity].runWith(client)
          }
        }

        "respond with error if the response body is empty" in {
          import io.circe.generic.auto._
          case class InvalidEntity(something: Boolean)
          implicit val entityPipe: Pipe[IO, Json, InvalidEntity] =
            io.bartholomews.fsclient.implicits.deriveJsonPipe[IO, InvalidEntity]
          assertDecodingFailure {
            validResponseGetEndpoint[InvalidEntity].runWith(client)
          }
        }
      }

      "response is 404" should {
        "retrieve the json response with Status NotFound and json error response" in {
          assertErrorJson(Status.NotFound, ExpectedErrorMessage.notFoundJson) {
            getJsonEndpoint[Json](notFoundJsonResponse).runWith(client)
          }
        }
      }

      "response is empty" should {

        def notFoundEmptyJsonResponseGetEndpoint[Res]: FsSimpleRequest.Get[Nothing, Json, Res] =
          getJsonEndpoint(notFoundEmptyJsonBodyResponse)

        "respond with error for http response timeout" taggedAs Slow in {
          assertEmptyResponseError {
            timeoutResponseGetEndpoint[Json].runWith(client)
          }
        }

        "return error with response status and default message" in {
          assertErrorString(Status.NotFound, ExpectedErrorMessage.emptyResponse) {
            notFoundEmptyJsonResponseGetEndpoint[Json].runWith(client)
          }
        }
      }

      "response has no `Content-Type`" should {
        "return the error status with the right message" in {
          assertErrorString(Status.BadRequest, expectedError = "") {
            getJsonEndpoint[Json](badRequestNoContentTypeNorBodyJsonResponse).runWith(client)
          }
        }
      }

      "response has an unexpected `Content-Type`" should {
        "return the error status with the right message" in {
          assertErrorString(Status.BadRequest, expectedError = "response=true&urlencoded=example") {
            getJsonEndpoint[Json](badRequestMultipartJsonResponse).runWith(client)
          }
        }
      }
    }
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

    "calling a GET endpoint with plainText response" when {

      "response is 200" should {

        "retrieve the response with Status Ok and string entity" in {
          assertRight(expectedEntity = "This is a valid plaintext response") {
            validPlainTextResponseGetEndpoint[String].runWith(client)
          }
        }

        "respond applying the provided `plainTextDecoder`" in {
          case class MyEntity(str: String)

          implicit val plainTextDecoder: Pipe[IO, String, MyEntity] =
            _.map(str => MyEntity(str))

          assertRight(expectedEntity = MyEntity("This is a valid plaintext response")) {
            validPlainTextResponseGetEndpoint[MyEntity].runWith(client)
          }
        }

        "respond with `EmptyResponseException` if the response body is empty" in {
          assertEmptyResponseError {
            getJsonEndpoint[String](okEmptyPlainTextResponse).runWith(client)
          }
        }
      }

      "response is 404" should {

        "retrieve the string response with Status NotFound" in {
          assertErrorString(Status.NotFound, ExpectedErrorMessage.notFoundString) {
            getJsonEndpoint[Json](notFoundPlainTextResponse).runWith(client)
          }
        }
      }

      "response is empty" should {

        "respond with error for http response timeout" taggedAs Slow in {
          assertEmptyResponseError {
            timeoutResponseGetEndpoint[Json].runWith(client)
          }
        }

        "return error with response status and empty message" in {
          assertErrorString(Status.NotFound, expectedError = "") {
            getJsonEndpoint[Json](notFoundEmptyPlainTextBodyResponse).runWith(client)
          }
        }
      }

      "response has an unexpected `Content-Type`" should {
        "return the error status with the right message" in {
          import io.bartholomews.fsclient.implicits._
          assertErrorString(Status.BadRequest, expectedError = "") {
            getJsonEndpoint[String](badRequestNoContentTypeNorBodyJsonResponse).runWith(client)
          }
        }
      }
    }

    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

    "calling a POST endpoint with entity body and json response" when {

      "response is 200" should {

        def validResponsePostJsonEndpoint[R]: FsSimpleRequest[MyRequestBody, Json, R] =
          postJsonEndpoint[MyRequestBody, R](okJsonResponse, requestBody)

        "retrieve the json with Status Ok and entity" in {
          assertRight(Map("message" -> "this is a json response").asJson) {
            validResponsePostJsonEndpoint[Json].runWith(client)
          }
        }

        "retrieve the decoded json with Status Ok and entity" in {
          import io.circe.generic.auto._
          case class ValidEntity(message: String)
          implicit val entityPipe: Pipe[IO, Json, ValidEntity] =
            io.bartholomews.fsclient.implicits.deriveJsonPipe[IO, ValidEntity]
          assertRight(ValidEntity("this is a json response")) {
            validResponsePostJsonEndpoint[ValidEntity].runWith(client)
          }
        }

        "respond with error if the response json is unexpected" in {
          import io.circe.generic.auto._
          case class InvalidEntity(something: Boolean)
          implicit val entityPipe: Pipe[IO, Json, InvalidEntity] =
            io.bartholomews.fsclient.implicits.deriveJsonPipe[IO, InvalidEntity]
          assertDecodingFailure {
            validResponsePostJsonEndpoint[InvalidEntity].runWith(client)
          }
        }

        "respond with error if the response body is empty" in {
          import io.circe.generic.auto._
          case class InvalidEntity(something: Boolean)
          implicit val entityPipe: Pipe[IO, Json, InvalidEntity] =
            io.bartholomews.fsclient.implicits.deriveJsonPipe[IO, InvalidEntity]
          assertDecodingFailure {
            validResponsePostJsonEndpoint[InvalidEntity].runWith(client)
          }
        }
      }

      "response is 404" should {
        def notFoundJsonResponsePostEndpoint[R]: FsSimpleRequest[MyRequestBody, Json, R] =
          postJsonEndpoint(notFoundJsonResponse, requestBody)

        "retrieve the json response with Status NotFound and json error response" in {
          assertErrorJson(Status.NotFound, ExpectedErrorMessage.notFoundJson) {
            notFoundJsonResponsePostEndpoint[Json].runWith(client)
          }
        }
      }

      "response is empty" should {
        def notFoundEmptyJsonResponsePostEndpoint[R]: FsSimpleRequest[MyRequestBody, Json, R] =
          postJsonEndpoint(notFoundEmptyJsonBodyResponse, requestBody)

        "respond with error for http response timeout" taggedAs Slow in {
          assertEmptyResponseError {
            timeoutResponsePostEndpoint[MyRequestBody, Json](requestBody).runWith(client)
          }
        }

        "return error with response status and default message" in {
          assertErrorString(Status.NotFound, ExpectedErrorMessage.emptyResponse) {
            notFoundEmptyJsonResponsePostEndpoint[Json].runWith(client)
          }
        }
      }

      "response has no `Content-Type`" should {
        "return error status with the right message" in {
          assertErrorString(Status.BadRequest, expectedError = "") {
            postJsonEndpoint[MyRequestBody, Json](badRequestNoContentTypeNorBodyJsonResponse, requestBody)
              .runWith(client)
          }
        }
      }
    }

    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

    "calling a POST endpoint with plainText response" when {

      "response is 200" should {

        "retrieve the response with Status Ok and string entity" in {
          assertRight("This is a valid plaintext response") {
            validPlainTextResponsePostEndpoint[MyRequestBody, String](requestBody).runWith(client)
          }
        }

        "respond applying the provided `plainTextDecoder`" in {
          case class MyEntity(str: Option[String])

          implicit val plainTextDecoder: Pipe[IO, String, MyEntity] =
            _.map(str => MyEntity(Some(str)))

          assertRight(MyEntity(Some("This is a valid plaintext response"))) {
            validPlainTextResponsePostEndpoint[MyRequestBody, MyEntity](requestBody).runWith(client)
          }
        }

        "respond with empty string if the response body is empty" in {
          assertRight(expectedEntity = "") {
            postPlainTextEndpoint[MyRequestBody, String](okEmptyPlainTextResponse, requestBody).runWith(client)
          }
        }
      }

      "response is 404" should {

        "retrieve the string response with Status NotFound" in {
          assertErrorString(Status.NotFound, ExpectedErrorMessage.notFoundString) {
            postJsonEndpoint[MyRequestBody, Json](notFoundPlainTextResponse, requestBody).runWith(client)
          }
        }
      }

      "response is empty" should {

        "respond with error for http response timeout" taggedAs Slow in {
          assertEmptyResponseError {
            timeoutResponsePostEndpoint[MyRequestBody, Json](requestBody).runWith(client)
          }
        }

        "return error with response status and empty message" in {
          assertErrorString(Status.NotFound, expectedError = "") {
            postJsonEndpoint[MyRequestBody, Json](notFoundEmptyPlainTextBodyResponse, requestBody).runWith(client)
          }
        }
      }

      "response has no `Content-Type`" should {
        "return the error status with the right message" in {
          import io.bartholomews.fsclient.implicits._
          assertErrorString(Status.BadRequest, expectedError = "") {
            postJsonEndpoint[MyRequestBody, String](badRequestNoContentTypeNorBodyJsonResponse, requestBody)
              .runWith(client)
          }
        }
      }

      "response has an unexpected `Content-Type`" should {
        "return the error status with the right message" in {
          assertErrorString(Status.BadRequest, expectedError = "response=true&urlencoded=example") {
            import io.bartholomews.fsclient.implicits._
            postJsonEndpoint[MyRequestBody, String](badRequestMultipartJsonResponse, requestBody).runWith(client)
          }
        }
      }

      // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    }
  }
}

// TODO: test V2
// TODO: test `oAuthClient` calls with valid and invalid token
// TODO: test `simpleClient.auth` calls with valid and invalid token