/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package unit.kafka.admin

import java.io.StringReader
import java.util.Properties

import kafka.admin.AclCommand
import kafka.security.auth._
import kafka.server.KafkaConfig
import kafka.utils.{Logging, TestUtils}
import kafka.zk.ZooKeeperTestHarness
import org.apache.kafka.common.security.auth.KafkaPrincipal
import org.junit.Test

class AclCommandTest extends ZooKeeperTestHarness with Logging {

  private val Users = Set(KafkaPrincipal.fromString("User:CN=writeuser,OU=Unknown,O=Unknown,L=Unknown,ST=Unknown,C=Unknown"), KafkaPrincipal.fromString("User:test2"))
  private val Hosts = Set("host1", "host2")
  private val HostsString = Hosts.mkString(AclCommand.Delimiter.toString)

  private val TopicResources = Set(new Resource(Topic, "test-1"), new Resource(Topic, "test-2"))
  private val GroupResources = Set(new Resource(Group, "testGroup-1"), new Resource(Group, "testGroup-2"))

  private val ResourceToCommand = Map[Set[Resource], Array[String]](
    TopicResources -> Array("--topic", "test-1,test-2"),
    Set(Resource.ClusterResource) -> Array("--cluster"),
    GroupResources -> Array("--group", "testGroup-1,testGroup-2")
  )

  private val ResourceToOperations = Map[Set[Resource], (Set[Operation], Array[String])](
    TopicResources -> (Set(Read, Write, Describe), Array("--operations", "Read,Write,Describe")),
    Set(Resource.ClusterResource) -> (Set(Create, ClusterAction), Array("--operations", "Create,ClusterAction")),
    GroupResources -> (Set(Read).toSet[Operation], Array("--operations", "Read"))
  )

  private val ProducerResourceToAcls = Map[Set[Resource], Set[Acl]](
    TopicResources -> AclCommand.getAcls(Users, Allow, Set(Write, Describe), Hosts),
    Set(Resource.ClusterResource) -> AclCommand.getAcls(Users, Allow, Set(Create), Hosts)
  )

  private val ConsumerResourceToAcls = Map[Set[Resource], Set[Acl]](
    TopicResources -> AclCommand.getAcls(Users, Allow, Set(Read, Describe), Hosts),
    GroupResources -> AclCommand.getAcls(Users, Allow, Set(Read), Hosts)
  )

  private val CmdToResourcesToAcl = Map[Array[String], Map[Set[Resource], Set[Acl]]](
    Array[String]("--producer") -> ProducerResourceToAcls,
    Array[String]("--consumer") -> ConsumerResourceToAcls,
    Array[String]("--producer", "--consumer") -> ConsumerResourceToAcls.map { case (k, v) => k -> (v ++
      ProducerResourceToAcls.getOrElse(k, Set.empty[Acl])) }
  )

  @Test
  def testAclCli() {
    val brokerProps = TestUtils.createBrokerConfig(0, zkConnect)
    brokerProps.put(KafkaConfig.AuthorizerClassNameProp, "kafka.security.auth.SimpleAclAuthorizer")
    val args = Array("--authorizer-properties", "zookeeper.connect=" + zkConnect)

    for ((resources, resourceCmd) <- ResourceToCommand) {
      for (permissionType <- PermissionType.values) {
        val operationToCmd = ResourceToOperations(resources)
        val (acls, cmd) = getAclToCommand(permissionType, operationToCmd._1)
          AclCommand.main(args ++ cmd ++ resourceCmd ++ operationToCmd._2 :+ "--add")
          for (resource <- resources) {
            TestUtils.waitAndVerifyAcls(acls, getAuthorizer(brokerProps), resource)
          }

          testRemove(resources, resourceCmd, args, brokerProps)
      }
    }
  }

  @Test
  def testProducerConsumerCli() {
    val brokerProps = TestUtils.createBrokerConfig(0, zkConnect)
    brokerProps.put(KafkaConfig.AuthorizerClassNameProp, "kafka.security.auth.SimpleAclAuthorizer")
    val args = Array("--authorizer-properties", "zookeeper.connect=" + zkConnect)

    for ((cmd, resourcesToAcls) <- CmdToResourcesToAcl) {
      val resourceCommand: Array[String] = resourcesToAcls.keys.map(ResourceToCommand).foldLeft(Array[String]())(_ ++ _)
      AclCommand.main(args ++ getCmd(Allow) ++ resourceCommand ++ cmd :+ "--add")
      for ((resources, acls) <- resourcesToAcls) {
        for (resource <- resources) {
          TestUtils.waitAndVerifyAcls(acls, getAuthorizer(brokerProps), resource)
        }
      }
      testRemove(resourcesToAcls.keys.flatten.toSet, resourceCommand, args, brokerProps)
    }
  }

  private def testRemove(resources: Set[Resource], resourceCmd: Array[String], args: Array[String], brokerProps: Properties) {
    for (resource <- resources) {
      Console.withIn(new StringReader(s"y${AclCommand.Newline}" * resources.size)) {
        AclCommand.main(args ++ resourceCmd :+ "--remove")
        TestUtils.waitAndVerifyAcls(Set.empty[Acl], getAuthorizer(brokerProps), resource)
      }
    }
  }

  private def getAclToCommand(permissionType: PermissionType, operations: Set[Operation]): (Set[Acl], Array[String]) = {
    (AclCommand.getAcls(Users, permissionType, operations, Hosts), getCmd(permissionType))
  }

  private def getCmd(permissionType: PermissionType): Array[String] = {
    val principalCmd = if (permissionType == Allow) "--allow-principal" else "--deny-principal"
    val hostCmd = if (permissionType == Allow) "--allow-hosts" else "--deny-hosts"

    val cmd = Array(hostCmd, HostsString)
    Users.foldLeft(cmd) ((cmd, user) => cmd ++ Array(principalCmd, user.toString))
  }

  def getAuthorizer(props: Properties): Authorizer = {
    val kafkaConfig = KafkaConfig.fromProps(props)
    val authZ = new SimpleAclAuthorizer
    authZ.configure(kafkaConfig.originals)

    authZ
  }
}
