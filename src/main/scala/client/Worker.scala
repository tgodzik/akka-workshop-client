package client

import akka.actor.{Actor, Props}
import com.virtuslab.akkaworkshop.Decrypter
import com.virtuslab.akkaworkshop.PasswordsDistributor.EncryptedPassword
import scala.util.Try

class Worker extends Actor {

  import RequesterActor._

  val decrypter = new Decrypter

  private def decryptPassword(password: String): Try[String] = Try {
    val prepared = decrypter.prepare(password)
    val decoded = decrypter.decode(prepared)
    val decrypted = decrypter.decrypt(decoded)
    decrypted
  }

  override def receive: Receive = working

  def working : Receive = {
    case ep@EncryptedPassword(encryptedPassword) =>
      val decrypted = decryptPassword(encryptedPassword)
      decrypted.map {
        decryptedPassword =>
          sender ! validatePasswordMessage("", encryptedPassword, decryptedPassword)
      }.getOrElse(self forward ep)
  }
}

object Worker {
  def props = Props[Worker]
}