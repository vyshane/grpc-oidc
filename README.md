# grpc-oidc

[OpenID Connect](http://openid.net/connect/) integration for [gRPC](https://grpc.io), in Scala.

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

First, define a case class to hold the authentication context.

```scala
case class AuthenticationContext(email: String, displayName: String)
```

You will need to provide a claims reader with the type `IDTokenClaimsSet => A` to the `IdTokenInterceptor[A]`. For convenience you can do that using the companion object.

```scala
object AuthenticationContext {
  // A claims reader that constructs an AuthenticationContext
  def apply(claims: IDTokenClaimsSet): AuthenticationContext = {
    AuthenticationContext(claims.getStringClaim("email"), claims.getStringClaim("name"))
  }
}
```

Next, create and add the `IdTokenInterceptor` when you start the gRPC server.

```scala
val config = ConfigFactory.load()
val interceptor = IdTokenInterceptor(AuthenticationContext(_), config)

val grpcServer = NettyServerBuilder
  .forPort(config.getInt("grpc.port"))
  .addService(ServerInterceptors.intercept(userService, interceptor))
  .build()
  .start()
```

You can then query for the `AuthenticationContext` in your service endpoint implementation.

```scala
val currentUser: Option[AuthenticationContext] =
  IdTokenInterceptor.getIdTokenContext[AuthenticationContext]()
```

### Alternative Configuration

You can also construct the interceptor in code:

```scala
val interceptor = IdTokenInterceptor(AuthenticationContext(_),
                                     "https://accounts.google.com",
                                     "myapp",
                                     JWSAlgorithm.RS256,
                                     "https://www.googleapis.com/oauth2/v3/certs")

```