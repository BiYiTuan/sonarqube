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
package org.sonar.server.issue.index;

import com.google.common.collect.Maps;
import org.apache.commons.lang.StringUtils;
import org.sonar.api.rule.RuleKey;
import org.sonar.server.db.DbClient;
import org.sonar.server.db.ResultSetIterator;
import org.sonar.server.db.migrations.SqlUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Date;

/**
 * Scrolls over table ISSUES and reads documents to populate
 * the issues index
 */
class IssueResultSetIterator extends ResultSetIterator<IssueDoc> {

  private static final String[] FIELDS = {
    // column 1
    "i.kee",
    "root.uuid",
    "i.updated_at",
    "i.created_at",
    "i.action_plan_key",
    "i.assignee",
    "i.effort_to_fix",
    "i.issue_attributes",
    "i.line",
    "i.message",

    // column 11
    "i.resolution",
    "i.severity",
    "i.status",
    "i.technical_debt",
    "i.reporter",
    "i.author_login",
    "i.issue_close_date",
    "i.issue_creation_date",
    "i.issue_update_date",
    "r.plugin_name",

    // column 21
    "r.plugin_rule_key",
    "r.language",
    "p.uuid",
    "p.module_uuid",
    "p.module_uuid_path",
    "p.path"
  };

  private static final String SQL_ALL = "select " + StringUtils.join(FIELDS, ",") + " from issues i " +
    "inner join rules r on r.id=i.rule_id " +
    "inner join projects p on p.id=i.component_id " +
    "inner join projects root on root.id=i.root_component_id";

  private static final String SQL_AFTER_DATE = SQL_ALL + " where i.updated_at>?";

  static IssueResultSetIterator create(DbClient dbClient, Connection connection, long afterDate) {
    try {
      String sql = afterDate > 0L ? SQL_AFTER_DATE : SQL_ALL;
      PreparedStatement stmt = dbClient.newScrollingSelectStatement(connection, sql);
      if (afterDate > 0L) {
        stmt.setTimestamp(1, new Timestamp(afterDate));
      }
      return new IssueResultSetIterator(stmt);
    } catch (SQLException e) {
      throw new IllegalStateException("Fail to prepare SQL request to select all issues", e);
    }
  }

  private IssueResultSetIterator(PreparedStatement stmt) throws SQLException {
    super(stmt);
  }

  @Override
  protected IssueDoc read(ResultSet rs) throws SQLException {
    IssueDoc doc = new IssueDoc(Maps.<String, Object>newHashMapWithExpectedSize(30));

    String key = rs.getString(1);
    String projectUuid = rs.getString(2);

    // all the keys must be present, even if value is null
    doc.setKey(key);
    doc.setProjectUuid(projectUuid);
    doc.setUpdateDate(SqlUtil.getDate(rs, 3));
    doc.setCreationDate(new Date(rs.getTimestamp(4).getTime()));
    doc.setActionPlanKey(rs.getString(5));
    doc.setAssignee(rs.getString(6));
    doc.setEffortToFix(SqlUtil.getDouble(rs, 7));
    doc.setAttributes(rs.getString(8));
    doc.setLine(SqlUtil.getInt(rs, 9));
    doc.setMessage(rs.getString(10));
    doc.setResolution(rs.getString(11));
    doc.setSeverity(rs.getString(12));
    doc.setStatus(rs.getString(13));
    doc.setDebt(SqlUtil.getLong(rs, 14));
    doc.setReporter(rs.getString(15));
    doc.setAuthorLogin(rs.getString(16));
    doc.setFuncCloseDate(SqlUtil.getDate(rs, 17));
    doc.setFuncCreationDate(SqlUtil.getDate(rs, 18));
    doc.setFuncUpdateDate(SqlUtil.getDate(rs, 19));
    String ruleRepo = rs.getString(20);
    String ruleKey = rs.getString(21);
    doc.setRuleKey(RuleKey.of(ruleRepo, ruleKey).toString());
    doc.setLanguage(rs.getString(22));
    doc.setComponentUuid(rs.getString(23));
    doc.setModuleUuid(rs.getString(24));
    doc.setModuleUuidPath(rs.getString(25));
    doc.setFilePath(rs.getString(26));
    return doc;
  }
}