/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.zeppelin.sqlserver;

import org.apache.zeppelin.interpreter.Interpreter;
import org.apache.zeppelin.interpreter.InterpreterContext;
import org.apache.zeppelin.interpreter.InterpreterPropertyBuilder;
import org.apache.zeppelin.interpreter.InterpreterResult;
import org.apache.zeppelin.interpreter.thrift.InterpreterCompletion;
import org.apache.zeppelin.scheduler.Scheduler;
import org.apache.zeppelin.scheduler.SchedulerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.sql.*;

import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import com.google.common.base.Function;

/**
 * SQL Server interpreter v2 for Zeppelin.
 *
 * CONNECTION_STYLE:
 *  Notebook
 *    Single global connection.
 *    Connection is opened when notebook is opened and
 *    closed when notebook is closed
 *
 *  Paragraph
 *    Each paragraph has its own connection.
 *    Connection is opened and closed at each execution
 */
public class SqlServerInterpreter extends Interpreter
{
  private static final String VERSION = "0.7.0-1";

  private static final char NEWLINE = '\n';
  private static final char TAB = '\t';
  private static final String TABLE_MAGIC_TAG = "%table ";
  private static final String NOTEBOOK_CONNECTION_STYLE = "notebook";
  private static final String PARAGRAPH_CONNECTION_STYLE = "paragraph";

  private static final String DEFAULT_JDBC_URL = "jdbc:sqlserver://localhost:1433";
  private static final String DEFAULT_JDBC_USER_PASSWORD = "";
  private static final String DEFAULT_JDBC_USER_NAME = "zeppelin";
  private static final String DEFAULT_JDBC_DATABASE_NAME = "tempdb";
  private static final String DEFAULT_JDBC_DRIVER_NAME =
    "com.microsoft.sqlserver.jdbc.SQLServerDriver";
  private static final String DEFAULT_MAX_RESULT = "1000";
  private static final String DEFAULT_CONNECTION_STYLE = NOTEBOOK_CONNECTION_STYLE;

  private static final String SQLSERVER_SERVER_URL = "sqlserver.url";
  private static final String SQLSERVER_SERVER_USER = "sqlserver.user";
  private static final String SQLSERVER_SERVER_PASSWORD = "sqlserver.password";
  private static final String SQLSERVER_SERVER_DATABASE_NAME = "sqlserver.database";
  private static final String SQLSERVER_SERVER_DRIVER_NAME = "sqlserver.driver.name";
  private static final String SQLSERVER_SERVER_MAX_RESULT = "sqlserver.max.result";
  private static final String SQLSERVER_SERVER_CONNECTION_STYLE = "sqlserver.connections";

  private Logger _logger = LoggerFactory.getLogger(SqlServerInterpreter.class);
  private Connection _jdbcGlobalConnection;
  private int _maxRows = 1000;
  private boolean _useNotebookConnection = true;

  List<InterpreterCompletion> _completions = new ArrayList<>();

  public SqlServerInterpreter(Properties property) {
    super(property);
  }

  private Connection openSQLServerConnection()
  {
    try {
      if (_jdbcGlobalConnection != null && _useNotebookConnection) {
        if (!_jdbcGlobalConnection.isClosed())
          return _jdbcGlobalConnection;
        else
          _logger.debug("Notebook connection is closed");
      }
    } catch (SQLException e)
    {
      _logger.error("Exception trapped while checking if connection is closed", e);
      return null;
    }

    _logger.debug("Opening SQL Server connection");
    Connection jdbcConnection;

    try
    {
      String driverName = getProperty(SQLSERVER_SERVER_DRIVER_NAME);
      String url = getProperty(SQLSERVER_SERVER_URL);
      String user = getProperty(SQLSERVER_SERVER_USER);
      String password = getProperty(SQLSERVER_SERVER_PASSWORD);
      String database = getProperty(SQLSERVER_SERVER_DATABASE_NAME);

      _maxRows = Integer.valueOf(getProperty(SQLSERVER_SERVER_MAX_RESULT));

      Class.forName(driverName);

      url = url + ";databaseName=" + database;
      jdbcConnection = DriverManager.getConnection(url, user, password);

    } catch (ClassNotFoundException | SQLException e)
    {
      _logger.error("Cannot open connection", e);
      return null;
    }

    if (jdbcConnection != null)
    {
      _logger.debug("Connection opened successfully");
    }

    if (_useNotebookConnection)
      _jdbcGlobalConnection = jdbcConnection;
    else
      _jdbcGlobalConnection = null;

    return jdbcConnection;
  }

  private void closeSQLServerConnection(Connection jdbcConnection)
  {
    closeSQLServerConnection(jdbcConnection, false);
  }

  private void closeSQLServerConnection(Connection jdbcConnection, boolean force)
  {
    if (_useNotebookConnection && !force) return;

    try
    {
      if (jdbcConnection != null)
        jdbcConnection.close();
    } catch (SQLException e)
    {
      _logger.error("Cannot close connection.", e);
    }
  }

  private String replaceTableSpecialChar(String input) {
    if (input == null)
      return "";

    return input.replace(TAB, ' ').replace(NEWLINE, ' ');
  }

  private InterpreterResult executeMetaCommand(String cmd, InterpreterContext ctx)
  {
    _logger.debug("Meta Command: '" + cmd + "'");

    InterpreterResult.Code result = InterpreterResult.Code.SUCCESS;
    StringBuilder resultMessage = new StringBuilder();

    if (cmd.toLowerCase().trim().equals(":help"))
    {
      Hashtable<String, String> commands = new Hashtable<>();
      commands.put("help", "This help");
      commands.put("info", "Information on used SQL Server interpreter");

      resultMessage
        .append(TABLE_MAGIC_TAG)
        .append("MetaCommand").append(TAB).append("Description").append(NEWLINE);

      for (Map.Entry<String, String> command : commands.entrySet())
      {
        resultMessage.append(command.getKey()).append(TAB)
          .append(command.getValue()).append(NEWLINE);
      }
    }

    if (cmd.toLowerCase().trim().equals(":info"))
    {
      resultMessage
        .append(String.format("Interpreter version: %1s", VERSION))
        .append(NEWLINE)
        .append(String.format("Using notebook connection: %1s", _useNotebookConnection))
        .append(NEWLINE)
        .append(String.format("Apache Zeppelin scheduler type: %1s",
                _useNotebookConnection ? "FIFO" : "Parallel"));
    }

    if (resultMessage.length() == 0)
    {
      result = InterpreterResult.Code.ERROR;
      resultMessage.append("Meta-command not known");
    }

    return new InterpreterResult(result, resultMessage.toString());
  }

  @Override
  public void open() {
    _logger.info(String.format("Starting T-SQL Interpreter v %1s", VERSION));

    String connectionStyle = getProperty(SQLSERVER_SERVER_CONNECTION_STYLE);
    _logger.info(String.format("Connection style: %1s", connectionStyle));
    _useNotebookConnection = !(connectionStyle.toLowerCase().equals(PARAGRAPH_CONNECTION_STYLE));

    _logger.debug("Loading completions");

    String keywords = "";
    try {
      String keywordsTSQL = new BufferedReader(new InputStreamReader(
              SqlServerInterpreter.class.getResourceAsStream("/t-sql.txt"))).readLine();

      keywords += keywordsTSQL.replaceAll("\n", ",").toUpperCase();

      String keywordsODBC = new BufferedReader(new InputStreamReader(
              SqlServerInterpreter.class.getResourceAsStream("/odbc.txt"))).readLine();

      keywords += "," + keywordsODBC.replaceAll("\n", ",").toUpperCase();

      // Also allow lower-case versions of all the keywords
      keywords += "," + keywords.toLowerCase();

      StringTokenizer tok = new StringTokenizer(keywords, ", ");
      while (tok.hasMoreTokens()) {
        String keyword = tok.nextToken();
        _completions.add(new InterpreterCompletion(keyword, keyword));
      }

      _logger.debug(String.format("Done: %1$d keywords added", _completions.size()));
    }
    catch (IOException  e)
    {
      logger.error("Error while loading keywords", e);
      _completions = new ArrayList<>();
    }

    Connection jdbcConnection = openSQLServerConnection();
    closeSQLServerConnection(jdbcConnection);

    _logger.debug("Done");
  }

  @Override
  public void close() {
    _logger.info("Releasing SQL Server Interpreter");

    closeSQLServerConnection(_jdbcGlobalConnection, true);
  }

  @Override
  public InterpreterResult interpret(String cmd, InterpreterContext ctx) {
    InterpreterResult.Code result;
    StringBuilder resultMessage = new StringBuilder();

    if (cmd.startsWith(":")) {
      return executeMetaCommand(cmd, ctx);
    }

    _logger.debug("T-SQL command: '" + cmd + "'");

    Connection jdbcConnection = openSQLServerConnection();
    if (jdbcConnection == null) {
      return new InterpreterResult(InterpreterResult.Code.ERROR,
        "Cannot open connection to SQL Server.");
    }

    Statement stmt;
    try {
      stmt = jdbcConnection.createStatement();
      stmt.setMaxRows(_maxRows);

      boolean hasResultSet = stmt.execute(cmd);

      if (hasResultSet) {
        ResultSet resultSet = stmt.getResultSet();
        ResultSetMetaData md = resultSet.getMetaData();

        resultMessage.append(TABLE_MAGIC_TAG);

        int columns = md.getColumnCount();

        // Table Header
        for (int i = 1; i <= columns; i++) {
          resultMessage.append(md.getColumnName(i));
          if (i < columns) resultMessage.append(TAB);
        }
        resultMessage.append(NEWLINE);

        // Table Body
        while (resultSet.next()) {
          for (int i = 1; i <= columns; i++) {
            resultMessage.append(replaceTableSpecialChar(resultSet.getString(i)));
            if (i < columns) resultMessage.append(TAB);
          }
          resultMessage.append(NEWLINE);
        }

      } else {
        int rowsUpdated = stmt.getUpdateCount();
        if (rowsUpdated >= 0)
          resultMessage.append(String.format("%1$d records affected.", rowsUpdated));
        else
          resultMessage.append("Command executed successfully.");
      }
      result = InterpreterResult.Code.SUCCESS;
    }
    catch (SQLException e) {
      _logger.error("Cannot execute SQL Server statement.", e);
      resultMessage = new StringBuilder();
      resultMessage.append("Cannot execute SQL Server statement.").append(NEWLINE);
      resultMessage.append(e.getMessage()).append(NEWLINE);
      result = InterpreterResult.Code.ERROR;
    }

    closeSQLServerConnection(jdbcConnection);

    return new InterpreterResult(result, resultMessage.toString());
  }

  @Override
  public void cancel(InterpreterContext context) {
    _logger.info("Cancel not (yet) implemented");
  }

  @Override
  public FormType getFormType() {
    return FormType.SIMPLE;
  }

  @Override
  public int getProgress(InterpreterContext context) {
    return 0;
  }

  @Override
  public Scheduler getScheduler() {
    String schedulerName = SqlServerInterpreter.class.getName() + this.hashCode();
    return _useNotebookConnection ?
            SchedulerFactory.singleton().createOrGetFIFOScheduler(schedulerName) :
            SchedulerFactory.singleton().createOrGetParallelScheduler(schedulerName, 10);
  }

  @Override
  public List<InterpreterCompletion> completion(String buf, int cursor) {
    logger.debug("completion called");
    logger.debug(String.format("buf: %1$s", buf));
    logger.debug(String.format("cursor: %1$d", cursor));

    return _completions;
  }
}
