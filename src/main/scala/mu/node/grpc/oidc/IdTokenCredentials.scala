// Copyright 2018 Vy-Shane Xie Sin Fat

package mu.node.grpc.oidc

import java.util.concurrent.Executor

import io.grpc.{Attributes, CallCredentials, Metadata, MethodDescriptor}

case class IdTokenCredentials(idToken: String) extends CallCredentials {

  override def applyRequestMetadata(method: MethodDescriptor[_, _],
                                    attributes: Attributes,
                                    appExecutor: Executor,
                                    applier: CallCredentials.MetadataApplier): Unit = {
    appExecutor.execute(() => {
      val authorizationHeaderKey = Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER)
      val headers = new Metadata()
      headers.put(authorizationHeaderKey, "Bearer " + idToken)
      applier.apply(headers)
    })
  }

  override def thisUsesUnstableApi() = ()
}
