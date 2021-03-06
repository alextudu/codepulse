/*
 * Code Pulse: A real-time code coverage testing tool. For more information
 * see http://code-pulse.com
 *
 * Copyright (C) 2014 Applied Visions - http://securedecisions.avi.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.secdec.codepulse.tracer

import scala.collection.mutable.{ Map => MutableMap }
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

import akka.actor.ActorSystem
import com.secdec.codepulse.components.notifications.{ NotificationMessage, NotificationSettings, Notifications }
import com.secdec.codepulse.data.jsp.{ JasperJspMapper, JspMapper }
import com.secdec.codepulse.data.model.{ ProjectData, ProjectDataProvider, ProjectId }
import reactive.{ EventSource, Observing }

import com.codedx.codepulse.utility.Loggable

import bootstrap.liftweb.AppCleanup

object ProjectManager extends Loggable {
	lazy val defaultActorSystem = {
		val sys = ActorSystem("ProjectManagerSystem")
		AppCleanup.addShutdownHook { () =>
			sys.shutdown()
			sys.awaitTermination()
			logger.debug("Shutdown ProjectManager's ActorSystem")
		}
		sys
	}
}

class ProjectManager(val actorSystem: ActorSystem) extends Observing with Loggable {

	private val projects = MutableMap.empty[ProjectId, TracingTarget]
	private val allSessionProjects = MutableMap.empty[ProjectId, TracingTarget]
	private val pendingProjectDeletions = MutableMap.empty[TracingTarget, DeletionKey]
	private val dataProvider: ProjectDataProvider = projectDataProvider
	private val transientDataProvider: TransientTraceDataProvider = transientTraceDataProvider
	val projectListUpdates = new EventSource[Unit]

	/** Looks up a TracingTarget from the given `traceId` */
	def getProject(projectId: ProjectId): Option[TracingTarget] = projects.get(projectId)

	def getInclusiveProject(projectId: ProjectId): Option[TracingTarget] = allSessionProjects.get(projectId)

	def projectsIterator: Iterator[TracingTarget] = projects.valuesIterator

	private var nextProjectNum = dataProvider.maxProjectId + 1
	private val nextProjectNumLock = new Object {}
	private def getNextProjectId(): ProjectId = nextProjectNumLock.synchronized {
		var id = ProjectId(nextProjectNum)
		nextProjectNum += 1
		id
	}
	private def registerProjectId(id: ProjectId): Unit = nextProjectNumLock.synchronized {
		nextProjectNum = math.max(nextProjectNum, id.num + 1)
	}

	/** Creates and adds a new TracingTarget with the given `projectData`, and an
	  * automatically-selected ProjectId.
	  */
	def createProject(): ProjectId = {
		val projectId = getNextProjectId

		val data = dataProvider getProject projectId

		//TODO: make jspmapper configurable somehow
		registerProject(projectId, data, Some(JasperJspMapper(data.treeNodeData)))

		projectId
	}

	/** Creates a new TracingTarget based on the given `traceId` and `traceData`,
	  * and returns it after adding it to this TraceManager.
	  */
	private def registerProject(projectId: ProjectId, projectData: ProjectData, jspMapper: Option[JspMapper]) = {
		registerProjectId(projectId)

		val target = AkkaTracingTarget(actorSystem, projectId, projectData, transientDataProvider get projectId, jspMapper)
		projects.put(projectId, target)
		allSessionProjects.put(projectId, target)

		// cause a projectListUpdate when this project's name changes
		projectData.metadata.nameChanges ->> { projectListUpdates fire () }

		// also cause a projectListUpdate right now, since we're adding to the list
		projectListUpdates fire ()

		val subscriptionMade = target.subscribeToStateChanges { stateUpdates =>
			// trigger an update when the target state updates
			stateUpdates ->> { projectListUpdates fire () }
		}

		// wait up to 1 second for the subscription to be acknowledged
		Await.ready(subscriptionMade, atMost = 1.second)

		target
	}

	def removeUnloadedProject(projectId: ProjectId, reason: String): Option[TracingTarget] = {
		dataProvider removeProject projectId
		for (project <- projects remove projectId) yield {
			project.notifyLoadingFailed(reason)
			project
		}
	}

	def removeProject(project: TracingTarget) = {
		val (deletionKey, deletionFuture) = project.setDeletePending()

		val id = project.id
		projects remove id
		dataProvider removeProject id
		pendingProjectDeletions.remove(project)

		projectListUpdates.fire()
		project.finalizeDeletion(deletionKey)
	}

	def scheduleProjectDeletion(project: TracingTarget) = {
		val (deletionKey, deletionFuture) = project.setDeletePending()
		pendingProjectDeletions.put(project, deletionKey)

		// in 10 seconds, actually delete the project
		val finalizer = actorSystem.scheduler.scheduleOnce(15.seconds) {

			// Request the finalization of the project target's deletion.
			// Doing so returns a Future that will succeed if the target
			// transitioned to the Deleted state, or fail if the deletion
			// was canceled.
			project.finalizeDeletion(deletionKey) onComplete { result =>
				logger.debug(s"project.finalizeDeletion() finished with $result")
			}
		}

		deletionFuture onComplete {
			case Success(_) =>
				// actually perform the deletion at this point
				projects remove project.id
				dataProvider removeProject project.id
				pendingProjectDeletions.remove(project)
				projectListUpdates fire ()

			case Failure(e) =>
				// the deletion was probably canceled
				logger.error(s"Deletion failed or maybe canceled. Message says '${e.getMessage}'")
				finalizer.cancel()
				pendingProjectDeletions.remove(project)
		}

		deletionFuture
	}

	def cancelProjectDeletion(project: TracingTarget) = {
		val ack = project.cancelPendingDeletion

		ack onComplete {
			case Success(_) =>
				// the cancel request was acknowledged
				val projectName = project.projectData.metadata.name
				val msg = NotificationMessage.ProjectUndeletion(projectName)
				Notifications.enqueueNotification(msg, NotificationSettings.defaultDelayed(3000), persist = false)
			case Failure(e) =>
				logger.error(s"Canceling delete failed. Message says '${e.getMessage}'")
		}

		ack
	}

	/** For each tracing target, make sure all data has been flushed.
	  */
	def flushProjects = projects.values.foreach(_.projectData.flush)

	/* Initialization */

	// Load project data files that are stored by the save manager.
	for {
		id <- dataProvider.projectList
		data = dataProvider getProject id
	} {
		logger.debug(s"loaded project $id")
		//TODO: make jspmapper configurable somehow
		val target = registerProject(id, data, Some(JasperJspMapper(data.treeNodeData)))
		target.notifyLoadingFinished()
	}

	// Also make sure any dirty projects are saved when exiting
	AppCleanup.addPreShutdownHook { () =>
		flushProjects
		logger.debug("Flushed ProjectManager projects")
	}

}