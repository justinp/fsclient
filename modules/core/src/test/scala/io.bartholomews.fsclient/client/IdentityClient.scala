package io.bartholomews.fsclient.client

import io.bartholomews.fsclient.client.ClientData.{sampleConsumer, sampleUserAgent}
import io.bartholomews.fsclient.core.FsClient
import io.bartholomews.fsclient.core.oauth.{AuthDisabled, SignerV1}
import sttp.client3.{HttpURLConnectionBackend, Identity, SttpBackend}

trait IdentityClient {

  val backend: SttpBackend[Identity, Any] = HttpURLConnectionBackend()

  val clientNoAuth: FsClient[Identity, AuthDisabled.type] =
    FsClient(sampleUserAgent, AuthDisabled, backend)

  val clientCredentials: FsClient[Identity, SignerV1] =
    FsClient.v1.clientCredentials(sampleUserAgent, sampleConsumer)(backend)
}
