package shield.aws

import com.amazonaws.auth._
import com.amazonaws.auth.BasicSessionCredentials
import com.typesafe.config.Config
import spray.http.Uri

/**
 * Created by amaffei on 3/16/16.
 */

case class AWSSigningConfig (host : String, region : String, service : String, active : Boolean, credentialsProvider : AWSCredentialsProvider) {
  def isSession() : Boolean = {
    return credentialsProvider.getCredentials.isInstanceOf[BasicSessionCredentials]
  }

  def getAccessKey() : String = {
    credentialsProvider.getCredentials.getAWSAccessKeyId
  }

  def getSecretKey() : String = {
    credentialsProvider.getCredentials.getAWSSecretKey
  }

  def getToken() : String = {
    require(isSession(), "This CredentialProvider is not session based and therefore provides no token.")
    credentialsProvider.getCredentials.asInstanceOf[BasicSessionCredentials].getSessionToken
  }
}

object AWSSigningConfig {
  def apply(config : Config) = {
    val active = (config.hasPath("AWSSigning.active") && config.getBoolean("AWSSigning.active"))
    require(!active || config.hasPath("AWSSigning.service"), "If AWS signing is active a service must be provided.")
    require(!active || config.hasPath("AWSSigning.region"), "If AWS signing is active a region must be provided.")

    new AWSSigningConfig(Uri(config.getString("host")).authority.host.address,
      getOptionalString(config,"AWSSigning.region").getOrElse("").trim,
      getOptionalString(config,"AWSSigning.service").getOrElse("").trim,
      getOptionalBoolean(config, "AWSSigning.active").getOrElse(false),
      new DefaultAWSCredentialsProviderChain()
      )
  }

  def getOptionalString(config: Config, path: String): Option[String] = {
    if (config.hasPath(path)) {
      Some(config.getString(path))
    } else {
      None
    }
  }

  def getOptionalBoolean(config: Config, path: String): Option[Boolean] = {
    if (config.hasPath(path)) {
      Some(config.getBoolean(path))
    } else {
      None
    }
  }
}
