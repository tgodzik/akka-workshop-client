package client

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import com.virtuslab.akkaworkshop.Decrypter
import com.virtuslab.akkaworkshop.PasswordsDistributor._

import scala.util.Try


class RequesterActor(remote: ActorRef) extends Actor with ActorLogging {

  import RequesterActor._

  val decrypter = new Decrypter
  var token = ""
  val name = "Basics"

  private def decryptPassword(password: String): Try[String] = Try {
    val prepared = decrypter.prepare(password)
    val decoded = decrypter.decode(prepared)
    val decrypted = decrypter.decrypt(decoded)
    decrypted
  }

  override def preStart() = {
    remote ! registerMessage(name)
  }

  override def receive: Receive = starting

  def starting: Receive = {
    case Registered(newToken) =>
      log.info(s"Registered with token $newToken")
      token = newToken
      remote ! sendPasswordMessage(token)
      context.become(working)
  }

  def working: Receive = {
    case ep@EncryptedPassword(encryptedPassword) =>
      val decrypted = decryptPassword(encryptedPassword)
      decrypted.map {
        decryptedPassword =>
          remote ! validatePasswordMessage(token, encryptedPassword, decryptedPassword)
      }.getOrElse(self ! ep)

    case PasswordCorrect(decryptedPassword) =>
      log.info(s"Password $decryptedPassword was decrypted successfully")
      remote ! sendPasswordMessage(token)

    case PasswordIncorrect(decryptedPassword) =>
      log.error(s"Password $decryptedPassword was not decrypted correctly")
      remote ! sendPasswordMessage(token)
  }

}


object RequesterActor {

  def props(remote: ActorRef) = Props(classOf[RequesterActor], remote)

  // messages needed to communicate with the server
  def registerMessage(name: String) = Register(name)

  def validatePasswordMessage(token: Token,
                              encryptedPassword: String,
                              decryptedPassword: String) =
    ValidateDecodedPassword(token, encryptedPassword, decryptedPassword)

  def sendPasswordMessage(token: Token) = SendMeEncryptedPassword(token)


}
