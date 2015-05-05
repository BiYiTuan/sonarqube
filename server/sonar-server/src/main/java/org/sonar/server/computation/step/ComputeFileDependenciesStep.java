/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.sonar.server.computation.step;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.resources.Qualifiers;
import org.sonar.batch.protocol.Constants;
import org.sonar.batch.protocol.output.BatchReport;
import org.sonar.batch.protocol.output.BatchReportReader;
import org.sonar.graph.Cycle;
import org.sonar.graph.DirectedGraphAccessor;
import org.sonar.graph.Dsm;
import org.sonar.graph.DsmTopologicalSorter;
import org.sonar.graph.Edge;
import org.sonar.graph.IncrementalCyclesAndFESSolver;
import org.sonar.graph.MinimumFeedbackEdgeSetSolver;
import org.sonar.server.computation.ComputationContext;
import org.sonar.server.computation.design.DsmDataBuilder;
import org.sonar.server.computation.design.DsmDataEncoder;
import org.sonar.server.computation.design.FileDependenciesCache;
import org.sonar.server.computation.design.FileDependency;
import org.sonar.server.computation.measure.Measure;
import org.sonar.server.computation.measure.MeasuresCache;
import org.sonar.server.computation.source.ReportIterator;
import org.sonar.server.design.db.DsmDb;

import javax.annotation.CheckForNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class ComputeFileDependenciesStep implements ComputationStep {

  private static final Logger LOG = LoggerFactory.getLogger(ComputeFileDependenciesStep.class);

  private static final int MAX_DSM_DIMENSION = 200;

  // TODO use disk caches
  private final FileDependenciesCache fileDependenciesCache;
  private final MeasuresCache measuresCache;

  public ComputeFileDependenciesStep(FileDependenciesCache fileDependenciesCache, MeasuresCache measuresCache) {
    this.fileDependenciesCache = fileDependenciesCache;
    this.measuresCache = measuresCache;
  }

  @Override
  public String[] supportedProjectQualifiers() {
    return new String[] {Qualifiers.PROJECT};
  }

  @Override
  public void execute(ComputationContext context) {
    int rootComponentRef = context.getReportMetadata().getRootComponentRef();
    ComponentUuidsCache uuidsByRef = new ComponentUuidsCache(context.getReportReader());
    recursivelyProcessComponent(context, uuidsByRef, rootComponentRef, rootComponentRef);
  }

  private void recursivelyProcessComponent(ComputationContext context, ComponentUuidsCache uuidsByRef, int componentRef, int parentModuleRef) {
    BatchReportReader reportReader = context.getReportReader();
    BatchReport.Component component = reportReader.readComponent(componentRef);
    for (Integer childRef : component.getChildRefList()) {
      recursivelyProcessComponent(context, uuidsByRef, childRef, componentRef);
    }

    if (component.getType().equals(Constants.ComponentType.FILE)) {
      processFileDependencies(context, component);
    } else if (component.getType().equals(Constants.ComponentType.DIRECTORY)) {
      processDirDependencies(context, uuidsByRef, component);
    }
  }

  private void processFileDependencies(ComputationContext context, BatchReport.Component component) {
    File fileDependencyReport = context.getReportReader().readFileDependencies(component.getRef());
    if (fileDependencyReport != null) {
      ReportIterator<BatchReport.FileDependency> fileDependenciesIterator = new ReportIterator<>(fileDependencyReport, BatchReport.FileDependency.PARSER);
      try {
        while (fileDependenciesIterator.hasNext()) {
          BatchReport.FileDependency fileDependency = fileDependenciesIterator.next();
          fileDependenciesCache.addFileDependency(component.getRef(), new FileDependency(component.getRef(), fileDependency.getToFileRef(), fileDependency.getWeight()));
        }
      } finally {
        fileDependenciesIterator.close();
      }
    }
  }

  private void processDirDependencies(ComputationContext context, ComponentUuidsCache uuidsByRef, BatchReport.Component component) {
    Collection<FileDependency> fileDependencies = getDependenciesFromChildren(context, component.getRef());
    if (!fileDependencies.isEmpty()) {
      DependenciesGraph dependenciesGraph = new DependenciesGraph();
      for (FileDependency fileDependency : fileDependencies) {
        dependenciesGraph.addDependency(fileDependency);
      }

      if (dependenciesGraph.getVertices().size() > MAX_DSM_DIMENSION) {
        LOG.warn(String.format("Too many components under component '%s'. DSM will not be persisted.", component.getKey()));
        return;
      }

      IncrementalCyclesAndFESSolver<Integer> cycleDetector = new IncrementalCyclesAndFESSolver<>(dependenciesGraph, dependenciesGraph.getVertices());
      Set<Cycle> cycles = cycleDetector.getCycles();
      MinimumFeedbackEdgeSetSolver solver = new MinimumFeedbackEdgeSetSolver(cycles);
      Set<Edge> feedbackEdges = solver.getEdges();
      Dsm<Integer> dsm = new Dsm<>(dependenciesGraph, dependenciesGraph.getVertices(), feedbackEdges);
      DsmTopologicalSorter.sort(dsm);
      DsmDb.Data dsmData = DsmDataBuilder.build(dsm, uuidsByRef);

      Measure measure = new Measure();
      // TODO use a new metric
      measure.setMetricKey(CoreMetrics.DEPENDENCY_MATRIX_KEY);
      measure.setComponentUuid(component.getUuid());
      measure.setByteValue(DsmDataEncoder.encodeSourceData(dsmData));
      measuresCache.addMeasure(component.getRef(), measure);
    }

    // TODO add directory dependencies in cache
  }

  private Collection<FileDependency> getDependenciesFromChildren(ComputationContext context, int ref) {
    Collection<FileDependency> dependencies = new ArrayList<>();
    for (Integer child : context.getReportReader().readComponent(ref).getChildRefList()) {
      dependencies.addAll(fileDependenciesCache.getFileDependencies(child));
    }
    return dependencies;
  }

  private static class DependenciesGraph implements DirectedGraphAccessor<Integer, FileDependency> {

    private Set<Integer> vertices = new HashSet<>();
    private Map<Integer, Map<Integer, FileDependency>> outgoingDependenciesByComponent = new LinkedHashMap<>();
    private Map<Integer, Map<Integer, FileDependency>> incomingDependenciesByComponent = new LinkedHashMap<>();

    private void addDependency(FileDependency dependency) {
      this.vertices.add(dependency.getFrom());
      this.vertices.add(dependency.getTo());
      registerOutgoingDependency(dependency);
      registerIncomingDependency(dependency);
    }

    @Override
    @CheckForNull
    public FileDependency getEdge(Integer from, Integer to) {
      Map<Integer, FileDependency> map = outgoingDependenciesByComponent.get(from);
      if (map != null) {
        return map.get(to);
      }
      return null;
    }

    @Override
    public boolean hasEdge(Integer from, Integer to) {
      return getEdge(from, to) != null;
    }

    @Override
    public Set<Integer> getVertices() {
      return vertices;
    }

    @Override
    public Collection<FileDependency> getOutgoingEdges(Integer from) {
      Map<Integer, FileDependency> deps = outgoingDependenciesByComponent.get(from);
      if (deps != null) {
        return deps.values();
      }
      return Collections.emptyList();
    }

    @Override
    public Collection<FileDependency> getIncomingEdges(Integer to) {
      Map<Integer, FileDependency> deps = incomingDependenciesByComponent.get(to);
      if (deps != null) {
        return deps.values();
      }
      return Collections.emptyList();
    }

    private void registerOutgoingDependency(FileDependency dependency) {
      Map<Integer, FileDependency> outgoingDeps = outgoingDependenciesByComponent.get(dependency.getFrom());
      if (outgoingDeps == null) {
        outgoingDeps = new HashMap<>();
        outgoingDependenciesByComponent.put(dependency.getFrom(), outgoingDeps);
      }
      outgoingDeps.put(dependency.getTo(), dependency);
    }

    private void registerIncomingDependency(FileDependency dependency) {
      Map<Integer, FileDependency> incomingDeps = incomingDependenciesByComponent.get(dependency.getTo());
      if (incomingDeps == null) {
        incomingDeps = new HashMap<>();
        incomingDependenciesByComponent.put(dependency.getTo(), incomingDeps);
      }
      incomingDeps.put(dependency.getFrom(), dependency);
    }
  }

  @Override
  public String getDescription() {
    return "Compute file dependencies";
  }
}
