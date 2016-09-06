package shield.consul

import org.parboiled.common.Base64

case class ConsulKVP(CreateIndex: Int, ModifyIndex: Int, LockIndex: Int, Key: String, Flags: Int, Value: Option[String], Session: Option[String]) {
  def StringValue = new String(Base64.rfc2045().decode(Value.getOrElse("")))
}
