/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.rudder.services.policies

import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.repository.NodeGroupRepository
import com.normation.rudder.domain.policies._
import com.normation.rudder.domain._
import RudderLDAPConstants._
import com.normation.inventory.ldap.core.LDAPConstants.{
  A_OC,A_NAME,A_DESCRIPTION
}

import com.normation.inventory.domain.NodeId

import net.liftweb.common.{Box,Failure,Full,EmptyBox,Empty}
import Box._

import com.normation.ldap.sdk._
import BuildFilter._
import com.normation.rudder.repository.ldap.LDAPEntityMapper
import com.normation.utils.Control.sequence


trait PolicyInstanceTargetService {
  
  /**
   * Find all target matching the given server UUID.
   * The result should never be empty (at least 
   * the self target should be returned).
   * That method is not supposed to try to update anything
   * about target, especially, it is not supposed to
   * try to update Dynamic Group cache information (if
   * implementation provides one). 
   * The goal is to be quick. 
   */
  def findTargets(nodeIds:Seq[NodeId]) : Box[Map[NodeId,Set[PolicyInstanceTarget]]]
  
  /**
   * For a given target, find back its NodeIds
   */
  def getNodeIds(target:PolicyInstanceTarget) : Box[Seq[NodeId]]
 
  /**
   * Get all information related to the target (isActivated, etc).
   * @param target
   * @return Return empty is no such target exists, Failure in case of an error, 
   *   Full(info) in case of success. 
   */
  def getTargetInfo(target:PolicyInstanceTarget) : Box[PolicyInstanceTargetInfo]
  
  /**
   * Retrieve all target defined in the system. 
   * @return
   */
  def getAllTargetInfo(includeSystem:Boolean = false) : Box[Seq[PolicyInstanceTargetInfo]]
  
}


class PolicyInstanceTargetServiceImpl(
    override val groupRepository : NodeGroupRepository,
    override val nodeInfoService : NodeInfoService, 
    override val units: Seq[UnitPolicyInstanceTargetService],
    override val ldap : LDAPConnectionProvider,
    override val rudderDit : RudderDit,
    override val mapper: LDAPEntityMapper
) extends PolicyInstanceTargetService with 
  PolicyInstanceTargetService_findTargets with 
  TargetToNodeIds with
  TargetInfoService

/////////////////////////////////////////////////////////////////////////////////////////////////  
  
trait TargetToNodeIds extends PolicyInstanceTargetService {
  
  def groupRepository : NodeGroupRepository
  def nodeInfoService : NodeInfoService
  
  override def getNodeIds(target:PolicyInstanceTarget) : Box[Seq[NodeId]] = {
    target match {
      case GroupTarget(groupId) => groupRepository.getNodeGroup(groupId).map(g => g.serverList.toSeq)
      case PolicyServerTarget(nodeId) => Full(Seq(nodeId))
      case AllTarget => nodeInfoService.getAllIds
      case AllTargetExceptPolicyServers => nodeInfoService.getAllUserNodeIds()
    }
  }
}


trait TargetInfoService extends  PolicyInstanceTargetService { 
  def groupRepository : NodeGroupRepository
  def nodeInfoService : NodeInfoService
  //for special target
  def ldap : LDAPConnectionProvider
  def rudderDit : RudderDit
  def mapper: LDAPEntityMapper

  /*
   * TODO: there is no real reason to take appart Group and other
   * targets. 
   * It may be clever to a TargetId LDAP attribute type, that
   * nodeId < targetId, and that all other targets have a 
   * targetId defined by convention, or why not one by
   * Ideas ex: special:all ==> targetId=all ; 
   *           policyServer:root => targetId=root ;
   * Potential problem: 
   * - how to deserialize ? (one sub attribute by type ?)
   * - overlaping ids. 
   */
  
  
  override def getTargetInfo(target:PolicyInstanceTarget) : Box[PolicyInstanceTargetInfo] = {
    //retrieve a target info from the target in LDAP directory
    //does no do any other validation than checking that the target is the actually retrieved target info
    def ldapGetTargetInfo : Box[PolicyInstanceTargetInfo] = {
      for {
        con <- ldap 
        entry <- con.get(rudderDit.GROUP.SYSTEM.targetDN(target)) ?~! "Error when fetching for target info entry with DN: %s".format(rudderDit.GROUP.SYSTEM.targetDN(target))
        targetInfo <- mapper.entry2PolicyInstanceTargetInfo(entry) ?~! "Error when mapping entry with DN '%s' into a policy instance target info".format(entry.dn)
        checkSameTarget <- if(target == targetInfo.target) Full("OK") else Failure("The retrieved target info does not match parameter target. Parameter target: '%s' ; retrieved target: '%s'".format(target, targetInfo.target))
      } yield {
        targetInfo
      }
    }
    
    target match {
      case GroupTarget(groupId) => groupRepository.getNodeGroup(groupId).map { g => 
        PolicyInstanceTargetInfo(target, g.name , g.description , g.isActivated , g.isSystem)
      }
      case PolicyServerTarget(nodeId) => 
        for {
          node <- nodeInfoService.getPolicyServerNodeInfo(nodeId) ?~! "Error when fetching for node %s".format(nodeId)
          targetInfo <- ldapGetTargetInfo
        } yield {
          targetInfo
        }
      //other policy 
      case AllTarget => ldapGetTargetInfo
      case AllTargetExceptPolicyServers => ldapGetTargetInfo
    }
  }
  
  override def getAllTargetInfo(includeSystem:Boolean = false) : Box[Seq[PolicyInstanceTargetInfo]] = {
    val base_filter = OR( IS(OC_RUDDER_NODE_GROUP), IS(OC_SPECIAL_TARGET))
    val filter = if(includeSystem) base_filter else AND(base_filter, EQ(A_IS_SYSTEM,false.toLDAPString))
    for {
      con <- ldap
      //for each pi entry, map it. if one fails, all fails
      targetInfos <- sequence(con.searchSub(rudderDit.GROUP.dn,  filter, A_OC, A_NODE_GROUP_UUID, A_NAME, A_POLICY_TARGET, A_DESCRIPTION, A_IS_ACTIVATED, A_IS_SYSTEM)) { entry => 
        mapper.entry2PolicyInstanceTargetInfo(entry) ?~! "Error when transforming LDAP entry into a Policy Instance Target Info. Entry: %s".format(entry)
      }
    } yield {
      targetInfos
    }
  }
  
}


///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////


trait PolicyInstanceTargetService_findTargets extends PolicyInstanceTargetService {

  def units: Seq[UnitPolicyInstanceTargetService]

  private def mergeMaps[T <: PolicyInstanceTarget](
    m0:Map[NodeId,Set[PolicyInstanceTarget]],
    m1:Map[NodeId,Set[T]]
  ) : Map[NodeId,Set[PolicyInstanceTarget]] = {
    
    (for {
      k <- m0.keySet ++ m1.keySet
    } yield {
      (
        k,
        m0.get(k).getOrElse(Set()) ++ m1.get(k).getOrElse(Set()) 
      )      
    }) toMap
    
  }
  
  /*
   * - Implementation strategy : all units processor need to succeed to lead to a
   *   Full(result).
   * - unit are processed on the order they were given to the constructor
   */
  override def findTargets(nodeIds:Seq[NodeId]) : Box[Map[NodeId,Set[PolicyInstanceTarget]]] = {
    ( (Full(Map()):Box[Map[NodeId,Set[PolicyInstanceTarget]]]) /: units) { case (box, processor) => box match {
      case e:EmptyBox => e
      case Full(state) => processor.findTargets(nodeIds) match {
        case e:EmptyBox => e
        case Full(newResults) => Full(mergeMaps(state,newResults))
      }
    } }
  }
}

/**
 * Specilize version of target service that only process one kind of target.
 * UnitPolicyInstanceTargetService == Upits
 */
trait UnitPolicyInstanceTargetService {
  type T <: PolicyInstanceTarget
  def findTargets(nodeIds:Seq[NodeId]) : Box[Map[NodeId,Set[T]]]
  
}


////////// Target processor for each target type //////////

/**
 * Implementation of UnitPolicyInstanceTargetService 
 * for Special:AllTarget.
 * 
 */
class SpecialAllTargetUpits extends UnitPolicyInstanceTargetService {  
  override type T = AllTarget.type
  
  /**
   * Each server has the "all" target
   */
  override def findTargets(nodeIds:Seq[NodeId]) : Box[Map[NodeId,Set[T]]] = {
      Full(nodeIds.map(uuid => (uuid,Set(AllTarget:T))).toMap)
  }
}

/**
 * Implementation of UnitPolicyInstanceTargetService 
 * for Special:AllTargetExceptPolicyServers
 * 
 */
class SpecialAllTargetExceptPolicyServersTargetUpits(
  nodeInfoService:NodeInfoService
) extends UnitPolicyInstanceTargetService {
  
  override type T = AllTargetExceptPolicyServers.type
  
  /**
   * Each server which is not a policy server has the AllTargetExceptPolicyServers
   */
  override def findTargets(nodeIds:Seq[NodeId]) : Box[Map[NodeId,Set[T]]] = {
    for {
      systemNodes <- nodeInfoService.getAllSystemNodeIds
    } yield {
      nodeIds.collect { case(id) if(!systemNodes.contains(id)) => (id, Set(AllTargetExceptPolicyServers:T)) }.toMap
    }
  }
}

/**
 * Implementation of UnitPolicyInstanceTargetService 
 * for PolicyServerTarget
 * 
 */
class PolicyServerTargetUpits(
  nodeInfoService:NodeInfoService
) extends UnitPolicyInstanceTargetService {
  
  override type T = PolicyServerTarget
  
  /**
   * Each server which is not a policy server has the AllTargetExceptPolicyServers
   */
  override def findTargets(nodeIds:Seq[NodeId]) : Box[Map[NodeId,Set[T]]] = {
    for {
      systemNodes <- nodeInfoService.getAllSystemNodeIds
    } yield {
      nodeIds.collect { case(id) if(systemNodes.contains(id)) => (id, Set(PolicyServerTarget(id):T)) }.toMap
    }
  }
}


/**
 * Implementation of UnitPolicyInstanceTargetService 
 * for PolicyServerTarget
 * 
 */
class GroupTargetUpits(
  groupRepository : NodeGroupRepository
) extends UnitPolicyInstanceTargetService {
  
  override type T = GroupTarget
  
  /**
   * Each server which is not a policy server has the AllTargetExceptPolicyServers
   */
  override def findTargets(nodeIds:Seq[NodeId]) : Box[Map[NodeId,Set[T]]] = {
    sequence(nodeIds.distinct) { nodeId =>
      groupRepository.findGroupWithAllMember(Seq(nodeId)).map(seq => (nodeId,seq.map(id => GroupTarget(id)).toSet))
    }.map( _.toMap )
  }
}