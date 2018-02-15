// Copyright 2018 Vy-Shane Xie Sin Fat

package mu.node.grpc.oidc

import java.net.URI
import java.util.UUID

import com.coreos.dex.api.{CreatePasswordReq, DexGrpc, Password => DexPassword}
import com.github.javafaker.Faker
import com.google.protobuf.ByteString
import com.nimbusds.oauth2.sdk._
import com.nimbusds.oauth2.sdk.auth.{ClientSecretBasic, Secret}
import com.nimbusds.oauth2.sdk.id.ClientID
import com.nimbusds.openid.connect.sdk.claims.IDTokenClaimsSet
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import io.grpc.inprocess.{InProcessChannelBuilder, InProcessServerBuilder}
import io.grpc.{ManagedChannelBuilder, ServerInterceptors, Status, StatusRuntimeException}
import mu.node.test.{PingRequest, PongResponse, TestServiceGrpc}
import org.jsoup.{Connection, Jsoup}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec}

import scala.concurrent.Future
import scala.concurrent.ExecutionContext

/*
 * This test launches a Dex Docker container and runs a full OAuth2 authorization code flow.
 *
 * We use the ID token obtained from Dex to perform a gRPC call that exercises adding
 * credentials to the client call using IdTokenCredentials.
 *
 * We then extract and validate the ID token using IdTokenInterceptor on the server side.
 * The end-to-end test also ensures that we can fetch the JSON Web Key Set from Dex.
 */
class IdTokenInterceptorEndToEnd extends WordSpec with Matchers with BeforeAndAfterAll with DockerDexService {

  val faker = new Faker()
  val firstName = faker.name().firstName()
  val email = faker.internet().emailAddress(firstName.toLowerCase)

  val serverName = s"grpc-oidc-test-${UUID.randomUUID().toString}"
  val clientId = "integration-test-app"
  val clientSecret = "ZXhhbXBsZS1hcHAtc2VjcmV0"
  val dexHost = "127.0.0.1"

  val server = {
    // Configure IdTokenInterceptor to connect to Dex Docker container
    val config = ConfigFactory
      .defaultApplication()
      .withValue("oidc.issuer", ConfigValueFactory.fromAnyRef(s"http://$dexHost:$dexHttpPort/dex"))
      .withValue("oidc.clientId", ConfigValueFactory.fromAnyRef(clientId))
      .withValue("oidc.jwsAlgorithm", ConfigValueFactory.fromAnyRef("RS256"))
      .withValue("oidc.jwksUrl", ConfigValueFactory.fromAnyRef(s"http://$dexHost:$dexHttpPort/dex/keys"))

    val idTokenInterceptor = IdTokenInterceptor(AuthenticatedContext(_), config)
    val testService = TestServiceGrpc.bindService(TestService(), ExecutionContext.global)

    InProcessServerBuilder
      .forName(serverName)
      .directExecutor()
      .addService(ServerInterceptors.intercept(testService, idTokenInterceptor))
      .build()
      .start()
  }

  val channel = InProcessChannelBuilder.forName(serverName).directExecutor().build()
  val testServiceStub = TestServiceGrpc.blockingStub(channel)

  override def afterAll() = {
    channel.shutdown()
    server.shutdown()
  }

  "IdTokenInterceptor" when {
    "intercepting an unauthenticated call" should {
      "not provide an authenticated context for the call" in {
        val error = intercept[StatusRuntimeException] {
          val updateInfo = testServiceStub.ping(PingRequest())
        }
        error.getStatus.getCode shouldEqual Status.UNAUTHENTICATED.getCode
      }
    }
    "intercepting an authenticated call" should {
      "provide an authenticated context for the call" in {
        // Setup Dex gRPC stub
        val dexChannel = ManagedChannelBuilder.forAddress(dexHost, dexGrpcPort).usePlaintext(true).build()
        val dexStub = DexGrpc.blockingStub(dexChannel)
        val userId = UUID.randomUUID().toString
        val password = faker.lorem().characters(8)

        // Register a user account with Dex
        import com.github.t3hnar.bcrypt._
        val request = buildCreatePasswordReq(userId, firstName, email, password.bcrypt)
        dexStub.createPassword(request)

        // Start a local HTTP server to provide the callback endpoint
        val callbackPort = 5555
        val callbackHttpServer = CallbackHttpServer(callbackPort, dexHost, dexHttpPort)
        callbackHttpServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)

        // Start of the OAuth2 flow
        val dexAuthorizationEndpoint = s"http://$dexHost:$dexHttpPort/dex/auth?" +
          s"client_id=$clientId&" +
          s"client_secret=$clientSecret&" +
          s"redirect_uri=http://127.0.0.1:$callbackPort/callback&" +
          "response_type=code&" +
          "scope=openid%20email%20profile%20groups%20offline_access"

        // Access the Dex authorization endpoint. Dex will redirect us to a login page.
        val loginUrl = Jsoup
          .connect(dexAuthorizationEndpoint)
          .method(Connection.Method.GET)
          .followRedirects(true)
          .execute()
          .url()

        // Submit the login form. Dex will redirect us to the grant access page.
        val grantAccessPage = Jsoup
          .connect(loginUrl.toString)
          .followRedirects(true)
          .data("login", email)
          .data("password", password)
          .post()

        // Submit grant access form
        // Dex will redirect us to our callback, which exchanges the authorization code for an access token
        // The callback endpoint prints the id token and we parse it from the page
        val req = grantAccessPage.select("input[name=req]").first().attr("value")
        val idToken = Jsoup
          .connect(grantAccessPage.location())
          .followRedirects(true)
          .method(Connection.Method.POST)
          .data("req", req)
          .data("approval", "approve")
          .post()
          .select("body")
          .first()
          .text()

        // Use the ID token to make an authenticated gRPC call to the test service
        noException should be thrownBy {
          testServiceStub
            .withCallCredentials(IdTokenCredentials(idToken))
            .ping(PingRequest())
        }

        // Cleanup
        callbackHttpServer.stop()
        dexChannel.shutdown()
      }
    }
  }

  /*
   * A test service implementation to verify that an authenticated RPC context can
   * be augmented with the call's ID token claims
   */
  case class TestService() extends TestServiceGrpc.TestService {

    override def ping(request: PingRequest): Future[PongResponse] = {
      import ExecutionContext.Implicits.global

      IdTokenInterceptor
        .getIdTokenContext[AuthenticatedContext]()
        // Ensure it's the same user
        .filter(ac => ac.email == email)
        .map(_ => Future(PongResponse()))
        .getOrElse(Future.failed(Status.UNAUTHENTICATED.asRuntimeException()))
    }
  }

  /*
   * HTTP server that implements the OAuth authorization code callback endpoint
   * and exchanges the authorization code for an access token using the Dex token endpoint
   */
  case class CallbackHttpServer(callbackPort: Int, dexHost: String, dexHttpPort: Int) extends NanoHTTPD(callbackPort) {

    // Callback endpoint prints the id token in the body of the HTML page
    override def serve(session: NanoHTTPD.IHTTPSession): Response = {
      val code = session.getParameters.get("code").get(0)
      val codeGrant =
        new AuthorizationCodeGrant(new AuthorizationCode(code), new URI(s"http://127.0.0.1:$callbackPort/callback"))
      val clientAuth =
        new ClientSecretBasic(new ClientID(clientId), new Secret("ZXhhbXBsZS1hcHAtc2VjcmV0"))
      val tokenRequest = new TokenRequest(new URI(s"http://$dexHost:$dexHttpPort/dex/token"), clientAuth, codeGrant)
      val tokenResponse = TokenResponse.parse(tokenRequest.toHTTPRequest().send())
      val idToken = tokenResponse
        .asInstanceOf[AccessTokenResponse]
        .getCustomParameters()
        .get("id_token")
        .asInstanceOf[String]
      NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/html; charset=utf-8", idToken)
    }
  }

  private def buildCreatePasswordReq(userId: String, displayName: String, email: String,
                                     passwordHash: String): CreatePasswordReq = {
    CreatePasswordReq(
      Some(
        DexPassword(email, ByteString.copyFromUtf8(passwordHash), displayName, userId)
      )
    )
  }
}

case class AuthenticatedContext(email: String)

object AuthenticatedContext {
  def apply(claims: IDTokenClaimsSet): AuthenticatedContext = AuthenticatedContext(claims.getStringClaim("email"))
}
