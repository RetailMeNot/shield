package shield.consul

import spray.json.DefaultJsonProtocol

object ConsulJsonProtocol extends DefaultJsonProtocol {
  implicit val valueFormat = jsonFormat7(ConsulKVP)
  implicit val serviceFormat = jsonFormat7(ConsulServiceNode)
}
