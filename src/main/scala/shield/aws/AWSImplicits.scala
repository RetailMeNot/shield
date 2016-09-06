package shield.aws

import shield.implicits.HttpImplicits
import spray.http.HttpRequest

/**
 * Implicitly adds the AWS authorization and date headers to a request.
 * Created by amaffei on 3/16/16.
 */
class AWSImplicits(msg: HttpRequest) {
  import HttpImplicits._
    def withAWSSigning(signingConfig: AWSSigningConfig): HttpRequest ={
      //Add auth and amz-date headers
      if(signingConfig.active) {
        msg.withAdditionalHeaders(AuthUtil.createAWSAuthorizationHeader(msg,signingConfig):_*)
      } else {
        msg
      }
    }
}

object AWSImplicits{
  implicit def AWSSignedRequest(request: HttpRequest) : AWSImplicits = new AWSImplicits(request)
}
