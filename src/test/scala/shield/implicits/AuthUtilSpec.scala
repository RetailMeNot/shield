package shield.implicits

import com.amazonaws.auth.{AWSCredentials, AWSCredentialsProvider, AWSCredentialsProviderChain}
import org.specs2.mutable.Specification
import shield.aws.{AWSSigningConfig, AuthUtil}
import spray.http._

/**
 * Created by amaffei on 3/15/16.
 */
class AuthUtilSpec extends Specification {
  //Set consistant times that will produce consistant results for the tests
  val d1 = "20160315T141234Z"
  val d2 = "20160315"

  //Create a new config, these values are typically found in application.conf
  val config = new AWSSigningConfig("example-elasticsearch-host", "us-west-1", "es", true, new AWSCredentialsProviderChain(new StaticCredentialProvider()))

  "AuthUtil" should {

    "Use SHA256" in {
      println(AuthUtil.hashAsString("Hello world!"))
      AuthUtil.hashAsString("Hello world!") must be equalTo "c0535e4be2b79ffd93291305436bf889314e4a3faec05ecffcbb7df31ad9e51a"
      AuthUtil.hashAsString("123$%^abcDEF") must be equalTo "3b43642576e2c2cf349f34ff7f10e700bf485e6982647a50e361e883a5aaafa2"
      AuthUtil.hashAsString("  _***~`  ") must be equalTo "0597e54e8278a8673f09842d03e4af3a2688d1a15a55a640968382a5311416b4"
    }

    "Create canonical request hash" in {
      val request = new HttpRequest(HttpMethods.GET, Uri("https://example-elasticsearch-host.com:80"), List(), HttpEntity(HttpData("Sample data for a sample request ~*)@#$) @#(((")))

      println(AuthUtil.createCanonicalHash(request, "example-elasticsearch-host"))
      AuthUtil.createCanonicalHash(request, "example-elasticsearch-host") must be equalTo "05ef99e67afa47f06ed12084460baa4fca0bfbf92faebabed00fa78796028c5d"
    }

    "Create string to sign from a given canonical request" in {
      val canonicalRequestHash = "05ef99e67afa47f06ed12084460baa4fca0bfbf92faebabed00fa78796028c5d"

      AuthUtil.createStringToSign(d1, d2, config.region, config.service, canonicalRequestHash) must be equalTo "AWS4-HMAC-SHA256\n20160315\n20160315T141234Z/us-west-1/es/aws4_request\n05ef99e67afa47f06ed12084460baa4fca0bfbf92faebabed00fa78796028c5d"
    }

    "Create a signature" in {
      val stringToSign = "AWS4-HMAC-SHA256\n20160315\n20160315T141234Z/us-west-1/es/aws4_request\n05ef99e67afa47f06ed12084460baa4fca0bfbf92faebabed00fa78796028c5d"
      val signature = AuthUtil.hmacSHA256AsString("AWS4-HMAC-SHA256\n20160315\n20160315T141234Z/us-west-1/es/aws4_request\n05ef99e67afa47f06ed12084460baa4fca0bfbf92faebabed00fa78796028c5d", AuthUtil.createSignatureKey(config.getSecretKey(), d1, config.region, config.service))

      signature must be equalTo "68e811337b35141320236cf585a7fefad71d8948e4d1e9d5eb3583474d31eb6a"
    }
  }
}

//Create a static credential provider so that the access key and secret key stay the same for the purposes of testing
class StaticCredentialProvider extends AWSCredentialsProvider {
  override def refresh(): Unit = { }

  override def getCredentials: AWSCredentials = new AWSCredentials {
    override def getAWSAccessKeyId: String = "AccessKeyId"

    override def getAWSSecretKey: String = "SuperSecretKey"
  }
}
