# grpc-oidc

[OpenID Connect](http://openid.net/connect/) integration for [gRPC](https://grpc.io), in Scala.

grpc-oidc authenticates RPC calls by checking the `Authorization` HTTP header for the ID token. It expects the header to be in this format:
 
```
Authorization: Bearer ${id_token}
```

## Example Usage

Add the grpc-oidc library to your `build.sbt`:

```
resolvers += Resolver.bintrayRepo("vyshane", "maven")
libraryDependencies += "mu.node" %% "grpc-oidc" % "0.1.0"
```

You can configure OIDC via [Lightbend Config](https://github.com/lightbend/config). Here's a sample application.conf:

```
 oidc {
   issuer="https://accounts.google.com"
   clientId="myapp"
   jwsAlgorithm="RS256"
   jwksUrl="https://www.googleapis.com/oauth2/v3/certs"
 }
```

### Server-side: Checking Authenticated Calls

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

#### Alternative Configuration

You can also configure the interceptor in code:

```scala
val interceptor = IdTokenInterceptor(AuthenticationContext(_),
                                     "https://accounts.google.com",
                                     "myapp",
                                     JWSAlgorithm.RS256,
                                     "https://www.googleapis.com/oauth2/v3/certs")

```

### Client-side: Making Authenticated Calls

You can use `IdTokenCredential` to make authenticated calls to a service. You pass in the ID token as a String and it will be sent as the Bearer token with the HTTP `Authorization` header. For example:

```scala
userServiceStub
  .withCallCredentials(IdTokenCredentials(idToken))
  .getMyProfile(GetMyProfileRequest())

```