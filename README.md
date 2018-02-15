# grpc-oidc

OpenID Connect integration for gRPC, in Scala.

## Example Usage

You can configure OIDC via [Lightbend Config](https://github.com/lightbend/config). Here's a sample application.conf:

```
 oidc {
   issuer="https://accounts.google.com"
   clientId="myapp"
   jwsAlgorithm="RS256"
   jwksUrl="https://www.googleapis.com/oauth2/v3/certs"
 }
```

Let's define a case class to hold our authentication context.

```scala
case class AuthenticationContext(email: String, displayName: String)

object AuthenticationContext {
  def apply(claims: IDTokenClaimsSet): AuthenticationContext = {
    AuthenticationContext(claims.getStringClaim("email"), claims.getStringClaim("name"))
  }
}
```

We add the `IdTokenInterceptor` to our gRPC server:

```scala
val config = ConfigFactory.load()
val idTokenInterceptor = IdTokenInterceptor(AuthenticationContext(_), config)

val grpcServer = NettyServerBuilder
  .forPort(config.getInt("grpc.port"))
  .addService(ServerInterceptors.intercept(userService, idTokenInterceptor))
  .build()
  .start()
```

In our service endpoint implementation, we can then query for the `AuthenticationContext`.

```scala
val currentUser: Option[AuthenticationContext] = IdTokenInterceptor.getIdTokenContext[AuthenticatedContext]()
```
