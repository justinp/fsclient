package fsclient.entities

import org.http4s.Status
import fsclient.utils.Logger

trait ResponseError extends Throwable {
  val status: Status

  override def getMessage: String
}

object ResponseError extends Logger {

  private case class ResponseErrorImpl(status: Status,
                                       throwable: Throwable,
                                       override val getMessage: String) extends ResponseError

  def apply(throwable: Throwable, status: Status = Status.InternalServerError): ResponseError = {
    throwable match {
      case circeError: io.circe.Error =>
        ResponseErrorImpl(
          Status.InternalServerError,
          circeError,
          "There was a problem decoding or parsing this response, please check the error logs"
        )
      case _ => ResponseErrorImpl(status, throwable, throwable.getMessage)
    }
  }

  def empty(status: Status = Status.BadRequest) = ResponseError.apply(EmptyResponseException, status)

  def empty: ResponseError = ResponseError.empty(Status.BadRequest)
}

case object EmptyResponseException extends Exception {
  override val getMessage: String = "Response was empty. Please check request logs"
}