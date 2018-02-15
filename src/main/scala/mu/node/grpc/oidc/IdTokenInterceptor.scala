// Copyright 2018 Vy-Shane Xie Sin Fat

package mu.node.grpc.oidc

import java.net.URL

import com.google.common.collect.Iterables
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jwt.JWTParser
import com.nimbusds.oauth2.sdk.id.{ClientID, Issuer}
import com.nimbusds.openid.connect.sdk.claims.IDTokenClaimsSet
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator
import com.typesafe.config.Config
import io.grpc._

import scala.util.Try

/*
 * The IdTokenInterceptor authenticates the gRPC call using OpenID Connect
 *
 * If the ID token is valid, we read the claims and inject authentication data
 * into the current RPC context
 */
class IdTokenInterceptor[A](readClaims: IDTokenClaimsSet => A,
                            issuer: String,
                            clientId: String,
                            jwsAlgorithm: JWSAlgorithm,
                            jwksUrl: URL)
    extends ServerInterceptor {

  private val validator = {
    val issuer = new Issuer(this.issuer)
    val clientID = new ClientID(this.clientId)
    val jwsAlgorithm = this.jwsAlgorithm
    val jwkSetUrl = this.jwksUrl
    new IDTokenValidator(issuer, clientID, jwsAlgorithm, jwkSetUrl)
  }

  override def interceptCall[ReqT, RespT](call: ServerCall[ReqT, RespT],
                                          headers: Metadata,
                                          next: ServerCallHandler[ReqT, RespT]): ServerCall.Listener[ReqT] = {
    val authenticatedContext = for {
      jwt <- extractIdToken(headers)
      claims <- validate(jwt)
      a = readClaims(claims)
      ctx = augmentCurrentContext(a)
    } yield ctx

    authenticatedContext match {
      case Some(ctx) => Contexts.interceptCall(ctx, call, headers, next)
      case None      => next.startCall(call, headers)
    }
  }

  private def extractIdToken(headers: Metadata): Option[String] = {
    val authorizationHeaderKey = Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER)
    Try {
      Iterables
        .toArray(headers.getAll(authorizationHeaderKey), classOf[String])
        .find(h => h.startsWith("Bearer "))
        .map(h => h.replaceFirst("Bearer ", ""))
    } getOrElse None
  }

  private def validate(jwt: String): Option[IDTokenClaimsSet] =
    Try(validator.validate(JWTParser.parse(jwt), null)).toOption

  private def augmentCurrentContext(a: A): Context =
    Context.current().withValue(IdTokenInterceptor.IdTokenContextKey, a)
}

object IdTokenInterceptor {

  def apply[A](readClaims: IDTokenClaimsSet => A,
               issuer: String,
               clientId: String,
               jwsAlgorithm: JWSAlgorithm,
               jwksUrl: URL): IdTokenInterceptor[A] = {
    new IdTokenInterceptor[A](readClaims, issuer, clientId, jwsAlgorithm, jwksUrl)
  }

  /*
   * Instantiate an IdTokenInterceptor by reading from Lightbend Config
   *
   * Sample HOCON configuration:
   *
   * oidc {
   *   issuer="https://accounts.google.com"
   *   clientId="myapp"
   *   jwsAlgorithm="RS256"
   *   jwksUrl="https://www.googleapis.com/oauth2/v3/certs"
   * }
   */
  def apply[A](readClaims: IDTokenClaimsSet => A, config: Config): IdTokenInterceptor[A] = {
    val issuer = config.getString("oidc.issuer")
    val clientID = config.getString("oidc.clientId")
    val jwsAlgorithm = JWSAlgorithm.parse(config.getString("oidc.jwsAlgorithm"))
    val jwkSetUrl = new URL(config.getString("oidc.jwksUrl"))
    new IdTokenInterceptor[A](readClaims, issuer, clientID, jwsAlgorithm, jwkSetUrl)
  }

  val IdTokenContextKey: Context.Key[Any] = Context.key(s"${IdTokenInterceptor.getClass.getCanonicalName}.usercontext")

  def getIdTokenContext[A](): Option[A] = Option[A](IdTokenContextKey.get().asInstanceOf[A])
}
