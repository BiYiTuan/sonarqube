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

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.batch.protocol.Constants;
import org.sonar.batch.protocol.output.BatchReport;
import org.sonar.batch.protocol.output.BatchReportReader;
import org.sonar.batch.protocol.output.BatchReportWriter;
import org.sonar.server.component.ComponentTesting;
import org.sonar.server.computation.ComputationContext;
import org.sonar.server.computation.design.DsmDataEncoder;
import org.sonar.server.computation.design.FileDependenciesCache;
import org.sonar.server.computation.measure.Measure;
import org.sonar.server.computation.measure.MeasuresCache;
import org.sonar.server.design.db.DsmDb;

import java.io.File;
import java.io.IOException;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

public class ComputeFileDependenciesStepTest extends BaseStepTest {

  static final String PROJECT_UUID = "PROJECT";

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  BatchReportWriter writer;

  ComputationContext context;

  ComputeFileDependenciesStep sut;

  FileDependenciesCache fileDependenciesCache;
  MeasuresCache measuresCache;

  @Before
  public void setup() throws Exception {
    File reportDir = temp.newFolder();
    writer = new BatchReportWriter(reportDir);
    writer.writeMetadata(BatchReport.Metadata.newBuilder()
      .setRootComponentRef(1)
      .build());
    context = new ComputationContext(new BatchReportReader(reportDir), ComponentTesting.newProjectDto(PROJECT_UUID));

    fileDependenciesCache = new FileDependenciesCache();
    measuresCache = new MeasuresCache();
    sut = new ComputeFileDependenciesStep(fileDependenciesCache, measuresCache);
  }

  @Override
  protected ComputationStep step() throws IOException {
    return new ComputeFileDependenciesStep(fileDependenciesCache, measuresCache);
  }

  @Test
  public void persist_dsm() throws Exception {
    writer.writeComponent(BatchReport.Component.newBuilder()
      .setRef(1)
      .setType(Constants.ComponentType.PROJECT)
      .setUuid(PROJECT_UUID)
      .addChildRef(2)
      .build());
    writer.writeComponent(BatchReport.Component.newBuilder()
      .setRef(2)
      .setType(Constants.ComponentType.DIRECTORY)
      .setUuid("DIRECTORY_A")
      .addChildRef(3)
      .build());
    writer.writeComponent(BatchReport.Component.newBuilder()
      .setRef(3)
      .setType(Constants.ComponentType.FILE)
      .setUuid("FILE_A")
      .build());
    writer.writeComponent(BatchReport.Component.newBuilder()
      .setRef(4)
      .setType(Constants.ComponentType.DIRECTORY)
      .setUuid("DIRECTORY_B")
      .addChildRef(5)
      .build());
    writer.writeComponent(BatchReport.Component.newBuilder()
      .setRef(5)
      .setType(Constants.ComponentType.FILE)
      .setUuid("FILE_B")
      .build());

    writer.writeFileDependencies(3, Collections.singletonList(
      BatchReport.FileDependency.newBuilder()
        .setToFileRef(5)
        .setWeight(1)
        .build()
    ));

    sut.execute(context);

    assertThat(measuresCache.getMeasures(2)).hasSize(1);

    Measure measure = measuresCache.getMeasures(2).iterator().next();
    assertThat(measure.getMetricKey()).isEqualTo(CoreMetrics.DEPENDENCY_MATRIX_KEY);
    assertThat(measure.getComponentUuid()).isEqualTo("DIRECTORY_A");
    assertThat(measure.getByteValue()).isNotNull();

    DsmDb.Data dsmData = DsmDataEncoder.decodeDsmData(measure.getByteValue());
    assertThat(dsmData.getUuid(0)).isEqualTo("FILE_A");
    assertThat(dsmData.getCellCount()).isEqualTo(1);
    assertThat(dsmData.getCell(0).getWeight()).isEqualTo(1);
    assertThat(dsmData.getCell(0).getOffset()).isEqualTo(1);
    assertThat(dsmData.getUuid(1)).isEqualTo("FILE_B");
  }

}
