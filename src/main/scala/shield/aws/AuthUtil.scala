package shield.aws

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import org.apache.commons.codec.binary.Hex
import org.joda.time.format.DateTimeFormat
import org.joda.time.{DateTime, DateTimeZone}
import spray.http.HttpHeaders.RawHeader
import spray.http.HttpRequest

/**
 * Utilities for signing AWS requests
 * Created by amaffei on 3/14/16.
 */
object AuthUtil {
  def hmacSHA256(data: String, key: Array[Byte]) : Array[Byte] = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(key, "HmacSHA256"))
    mac.doFinal(data.getBytes("UTF8"));
  }

  def hmacSHA256AsString(data: String, key: Array[Byte]) : String = {
    Hex.encodeHexString(hmacSHA256(data, key))
  }

  def hashAsString(data: String) : String = {
    val digest = MessageDigest.getInstance("SHA-256");
    HexBytesUtil.bytes2hex(digest.digest(data.getBytes(StandardCharsets.UTF_8)))
  }

  def createSignatureKey(key: String, date: String, region: String, service: String) : Array[Byte] = {
    val kSecret = ("AWS4" + key).getBytes("UTF8");
    val kDate    = hmacSHA256(date, kSecret);
    val kRegion  = hmacSHA256(region, kDate);
    val kService = hmacSHA256(service, kRegion);
    val kSigning = hmacSHA256("aws4_request", kService);
    kSigning
  }

  def createCanonicalHash(request: HttpRequest, host: String) : String = {
    hashAsString(request.method.name + "\n" + request.uri.path.toString() + "\n\nhost:" + host + "\n\nhost\n" + hashAsString(request.entity.data.asString))
  }

  def createStringToSign(d1: String, d2: String, region: String, service: String, canonicalHash: String) : String = {
    "AWS4-HMAC-SHA256\n" + d2 + "\n" + d1 + "/" + region + "/" + service + "/aws4_request\n" + canonicalHash
  }

  def createAWSAuthorizationHeader(request: HttpRequest, config: AWSSigningConfig) : Array[RawHeader] = {
    //This follows the AWS signing process that is outlined here: http://docs.aws.amazon.com/general/latest/gr/sigv4-create-canonical-request.html
    //get date in both forms
    val d2 = DateTimeFormat.forPattern("yyyyMMdd'T'HHmmss'Z'").print(DateTime.now(DateTimeZone.UTC))
    val d1 = DateTimeFormat.forPattern("yyyyMMdd").print(DateTime.now(DateTimeZone.UTC))

    //get string to sign
    val stringToSign = createStringToSign(d1, d2, config.region, config.service, createCanonicalHash(request,config.host))

    //make signing key
    val signingKey = createSignatureKey(config.getSecretKey(),d1,config.region,config.service)

    //make signature
    val signature = hmacSHA256AsString(stringToSign,signingKey)

    //make Auth header
    val header = "AWS4-HMAC-SHA256 Credential=" + config.getAccessKey() + "/" + d1 + "/" + config.region + "/" + config.service + "/aws4_request, SignedHeaders=host, Signature=" + signature
    var headers = Array(RawHeader("Authorization", header), RawHeader("X-Amz-Date", d2))

    //If using sessioned credentials then the token must be sent via the "X-Amz-Security-Token" header
    if(config.isSession())
      headers = headers :+ RawHeader("X-Amz-Security-Token",config.getToken())
    headers
  }
}

/**
 * https://gist.github.com/tmyymmt/3727124
 */
object HexBytesUtil {

  def hex2bytes(hex: String): Array[Byte] = {
    hex.replaceAll("[^0-9A-Fa-f]", "").sliding(2, 2).toArray.map(Integer.parseInt(_, 16).toByte)
  }

  def bytes2hex(bytes: Array[Byte], sep: Option[String] = None): String = {
    sep match {
      case None => bytes.map("%02x".format(_)).mkString
      case _ => bytes.map("%02x".format(_)).mkString(sep.get)
    }
  }
}