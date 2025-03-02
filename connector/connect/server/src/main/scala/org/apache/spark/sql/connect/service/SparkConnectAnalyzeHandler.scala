/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.connect.service

import scala.collection.JavaConverters._

import io.grpc.stub.StreamObserver

import org.apache.spark.connect.proto
import org.apache.spark.internal.Logging
import org.apache.spark.sql.{Dataset, SparkSession}
import org.apache.spark.sql.connect.common.{DataTypeProtoConverter, InvalidPlanInput}
import org.apache.spark.sql.connect.planner.SparkConnectPlanner
import org.apache.spark.sql.execution.{CodegenMode, CostMode, ExtendedMode, FormattedMode, SimpleMode}

private[connect] class SparkConnectAnalyzeHandler(
    responseObserver: StreamObserver[proto.AnalyzePlanResponse])
    extends Logging {

  def handle(request: proto.AnalyzePlanRequest): Unit = {
    val session =
      SparkConnectService
        .getOrCreateIsolatedSession(request.getUserContext.getUserId, request.getSessionId)
        .session
    session.withActive {
      val response = process(request, session)
      responseObserver.onNext(response)
      responseObserver.onCompleted()
    }
  }

  def process(
      request: proto.AnalyzePlanRequest,
      session: SparkSession): proto.AnalyzePlanResponse = {
    lazy val planner = new SparkConnectPlanner(session)
    val builder = proto.AnalyzePlanResponse.newBuilder()

    request.getAnalyzeCase match {
      case proto.AnalyzePlanRequest.AnalyzeCase.SCHEMA =>
        val schema = Dataset
          .ofRows(session, planner.transformRelation(request.getSchema.getPlan.getRoot))
          .schema
        builder.setSchema(
          proto.AnalyzePlanResponse.Schema
            .newBuilder()
            .setSchema(DataTypeProtoConverter.toConnectProtoType(schema))
            .build())

      case proto.AnalyzePlanRequest.AnalyzeCase.EXPLAIN =>
        val queryExecution = Dataset
          .ofRows(session, planner.transformRelation(request.getExplain.getPlan.getRoot))
          .queryExecution
        val explainString = request.getExplain.getExplainMode match {
          case proto.AnalyzePlanRequest.Explain.ExplainMode.EXPLAIN_MODE_SIMPLE =>
            queryExecution.explainString(SimpleMode)
          case proto.AnalyzePlanRequest.Explain.ExplainMode.EXPLAIN_MODE_EXTENDED =>
            queryExecution.explainString(ExtendedMode)
          case proto.AnalyzePlanRequest.Explain.ExplainMode.EXPLAIN_MODE_CODEGEN =>
            queryExecution.explainString(CodegenMode)
          case proto.AnalyzePlanRequest.Explain.ExplainMode.EXPLAIN_MODE_COST =>
            queryExecution.explainString(CostMode)
          case proto.AnalyzePlanRequest.Explain.ExplainMode.EXPLAIN_MODE_FORMATTED =>
            queryExecution.explainString(FormattedMode)
          case other => throw new UnsupportedOperationException(s"Unknown Explain Mode $other!")
        }
        builder.setExplain(
          proto.AnalyzePlanResponse.Explain
            .newBuilder()
            .setExplainString(explainString)
            .build())

      case proto.AnalyzePlanRequest.AnalyzeCase.TREE_STRING =>
        val treeString = Dataset
          .ofRows(session, planner.transformRelation(request.getTreeString.getPlan.getRoot))
          .schema
          .treeString
        builder.setTreeString(
          proto.AnalyzePlanResponse.TreeString
            .newBuilder()
            .setTreeString(treeString)
            .build())

      case proto.AnalyzePlanRequest.AnalyzeCase.IS_LOCAL =>
        val isLocal = Dataset
          .ofRows(session, planner.transformRelation(request.getIsLocal.getPlan.getRoot))
          .isLocal
        builder.setIsLocal(
          proto.AnalyzePlanResponse.IsLocal
            .newBuilder()
            .setIsLocal(isLocal)
            .build())

      case proto.AnalyzePlanRequest.AnalyzeCase.IS_STREAMING =>
        val isStreaming = Dataset
          .ofRows(session, planner.transformRelation(request.getIsStreaming.getPlan.getRoot))
          .isStreaming
        builder.setIsStreaming(
          proto.AnalyzePlanResponse.IsStreaming
            .newBuilder()
            .setIsStreaming(isStreaming)
            .build())

      case proto.AnalyzePlanRequest.AnalyzeCase.INPUT_FILES =>
        val inputFiles = Dataset
          .ofRows(session, planner.transformRelation(request.getInputFiles.getPlan.getRoot))
          .inputFiles
        builder.setInputFiles(
          proto.AnalyzePlanResponse.InputFiles
            .newBuilder()
            .addAllFiles(inputFiles.toSeq.asJava)
            .build())

      case proto.AnalyzePlanRequest.AnalyzeCase.SPARK_VERSION =>
        builder.setSparkVersion(
          proto.AnalyzePlanResponse.SparkVersion
            .newBuilder()
            .setVersion(session.version)
            .build())

      case proto.AnalyzePlanRequest.AnalyzeCase.DDL_PARSE =>
        val schema = planner.parseDatatypeString(request.getDdlParse.getDdlString)
        builder.setDdlParse(
          proto.AnalyzePlanResponse.DDLParse
            .newBuilder()
            .setParsed(DataTypeProtoConverter.toConnectProtoType(schema))
            .build())

      case proto.AnalyzePlanRequest.AnalyzeCase.SAME_SEMANTICS =>
        val target = Dataset.ofRows(
          session,
          planner.transformRelation(request.getSameSemantics.getTargetPlan.getRoot))
        val other = Dataset.ofRows(
          session,
          planner.transformRelation(request.getSameSemantics.getOtherPlan.getRoot))
        builder.setSameSemantics(
          proto.AnalyzePlanResponse.SameSemantics
            .newBuilder()
            .setResult(target.sameSemantics(other)))

      case other => throw InvalidPlanInput(s"Unknown Analyze Method $other!")
    }

    builder.setSessionId(request.getSessionId)
    builder.build()
  }
}
