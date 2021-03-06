// Copyright (c) 2016-2021 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package explore.common

import cats.effect._
import cats.implicits._
import eu.timepit.refined._
import eu.timepit.refined.collection.NonEmpty
import explore.model.SSOConfig
import explore.model.UserVault
import io.circe.Decoder
import io.circe.generic.semiauto._
import io.circe.parser._
import lucuma.core.model.User
import lucuma.sso.client.codec.user._
import org.scalajs.dom.experimental.RequestCredentials
import org.scalajs.dom.window
import org.typelevel.log4cats.Logger
import retry.RetryDetails._
import retry.RetryPolicies._
import retry._
import sttp.client3._
import sttp.client3.impl.cats.FetchCatsBackend

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.{ util => ju }
import scala.concurrent.duration._

import ju.concurrent.TimeUnit

final case class JwtOrcidProfile(exp: Long, `lucuma-user`: User)

object JwtOrcidProfile {
  implicit val decoder: Decoder[JwtOrcidProfile] = deriveDecoder
}

case class SSOClient[F[_]: Async: Logger](config: SSOConfig) {
  private val retryPolicy =
    capDelay(
      FiniteDuration.apply(5, TimeUnit.SECONDS),
      fullJitter[F](FiniteDuration.apply(10, TimeUnit.MILLISECONDS))
    ).join(limitRetries[F](12))

  private val backend = FetchCatsBackend(FetchOptions(RequestCredentials.include.some, none))

  def logError(msg: String)(err: Throwable, details: RetryDetails): F[Unit] = details match {
    case WillDelayAndRetry(_, retriesSoFar, _) =>
      Logger[F].warn(err)(s"$msg failed - Will retry. Retries so far: [$retriesSoFar]")

    case GivingUp(totalRetries, _) =>
      Logger[F].error(err)(s"$msg failed - Giving up after [$totalRetries] retries.")
  }

  // Does a client side redirect to the sso site
  val redirectToLogin: F[Unit] =
    Sync[F].delay {
      val returnUrl = window.location
      window.location.href = uri"${config.uri}/auth/v1/stage1?state=$returnUrl".toString
    }

  val guest: F[UserVault] =
    retryingOnAllErrors(retryPolicy, logError("Switching to guest")) {
      basicRequest
        .post(uri"${config.uri}/api/v1/auth-as-guest")
        .readTimeout(config.readTimeout)
        .send(backend)
        .flatMap {
          case Response(Right(body), _, _, _, _, _) =>
            Sync[F].delay {
              (for {
                k <- Either.catchNonFatal(
                       ju.Base64.getDecoder.decode(body.split('.')(1).replace("-", "+"))
                     )
                j  = new String(k)
                p <- parse(j)
                u <- p.as[JwtOrcidProfile]
                t <- refineV[NonEmpty](body)
              } yield UserVault(u.`lucuma-user`, Instant.ofEpochSecond(u.exp), t))
                .getOrElse(throw new RuntimeException("Error decoding the token"))
            }
          case Response(Left(e), _, _, _, _, _)     =>
            throw new RuntimeException(e)
        }
    }

  val whoami: F[Option[UserVault]] =
    retryingOnAllErrors(retryPolicy, logError("Calling whoami")) {
      basicRequest
        .post(uri"${config.uri}/api/v1/refresh-token")
        .readTimeout(config.readTimeout)
        .send(backend)
        .flatMap {
          case Response(Right(body), _, _, _, _, _) =>
            Sync[F].delay {
              (for {
                k <- Either.catchNonFatal(
                       ju.Base64.getDecoder.decode(body.split('.')(1).replace("-", "+"))
                     )
                j  = new String(k)
                p <- parse(j)
                u <- p.as[JwtOrcidProfile]
                t <- refineV[NonEmpty](body)
              } yield UserVault(u.`lucuma-user`, Instant.ofEpochSecond(u.exp), t).some)
                .getOrElse(throw new RuntimeException("Error decoding the token"))
            }
          case Response(Left(_), _, _, _, _, _)     =>
            none[UserVault].pure[F]
        }
    }.adaptError { case t =>
      new Exception("Error connecting to authentication server.", t)
    }

  def refreshToken(expiration: Instant): F[Option[UserVault]] =
    Sync[F].delay(Instant.now).flatMap { n =>
      val sleepTime = config.refreshTimeoutDelta.max(
        (n.until(expiration, ChronoUnit.SECONDS).seconds - config.refreshTimeoutDelta)
      )
      Temporal[F].sleep(sleepTime / config.refreshIntervalFactor)
    } >> whoami.flatTap(_ => Logger[F].info("User token refreshed"))

  val logout: F[Unit] =
    retryingOnAllErrors(retryPolicy, logError("Calling logout")) {
      basicRequest
        .post(uri"${config.uri}/api/v1/logout")
        .readTimeout(config.readTimeout)
        .send(backend)
        .void
    }

  val switchToORCID: F[Unit] =
    logout.attempt >> redirectToLogin
}
