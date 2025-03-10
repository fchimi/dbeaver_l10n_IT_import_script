/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2022 DBeaver Corp and others
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
package org.jkiss.dbeaver.ext.vertica.model;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.generic.model.GenericDataSource;
import org.jkiss.dbeaver.ext.generic.model.meta.GenericMetaModel;
import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.access.DBAUserPasswordManager;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCStatement;
import org.jkiss.dbeaver.model.impl.jdbc.cache.JDBCObjectCache;
import org.jkiss.dbeaver.model.meta.Association;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSStructureAssistant;

import java.sql.SQLException;
import java.util.Collection;
import java.util.Locale;

public class VerticaDataSource extends GenericDataSource {

    private Boolean childObjectColumnAvailable;

    private NodeCache nodeCache = new NodeCache();

    public VerticaDataSource(DBRProgressMonitor monitor, DBPDataSourceContainer container, GenericMetaModel metaModel)
        throws DBException
    {
        super(monitor, container, metaModel, new VerticaSQLDialect());
    }

    @Override
    protected boolean isPopulateClientAppName() {
        return false;
    }

    @Override
    public boolean isOmitCatalog() {
        return true;
    }

    @NotNull
    public DBPDataKind resolveDataKind(@NotNull String typeName, int valueType)
    {
        int divPos = typeName.indexOf('(');
        if (divPos != -1) {
            typeName = typeName.substring(0, divPos);
        }
        typeName = typeName.trim().toLowerCase(Locale.ENGLISH);
        switch (typeName) {
            case "binary":
            case "varbinary":
            case "long varbinary":
            case "bytea":
            case "raw":
                return DBPDataKind.BINARY;

            case "boolean":
                return DBPDataKind.BOOLEAN;

            case "char":
            case "varchar":
            case "long varchar":
                return DBPDataKind.STRING;

            case "date":
            case "datetime":
            case "smalldatetime":
            case "time":
            case "time with timezone":
            case "timetz":
            case "timestamp": case "timestamptz":
            case "timestamp with timezone":
            case "interval":
            case "interval day":
                return DBPDataKind.DATETIME;

            case "double precision":
            case "float":
            case "float8":
            case "real":

            case "integer":
            case "int":
            case "bigint":
            case "int8":
            case "smallint":
            case "tinyint":
            case "decimal":
            case "numeric":
            case "number":
            case "money":
                return DBPDataKind.NUMERIC;

            default:
                return DBPDataKind.OBJECT;
        }
    }

    @Association
    public Collection<VerticaNode> getClusterNodes(DBRProgressMonitor monitor) throws DBException {
        return nodeCache.getAllObjects(monitor, this);
    }

    public VerticaNode getClusterNode(DBRProgressMonitor monitor, String name) throws DBException {
        return nodeCache.getObject(monitor, this, name);
    }

    @Override
    public <T> T getAdapter(Class<T> adapter) {
        if (adapter == DBAUserPasswordManager.class) {
            return adapter.cast(new VerticaChangeUserPasswordManager(this));
        } else if (adapter == DBSStructureAssistant.class) {
            return adapter.cast(new VerticaStructureAssistant(this));
        }
        return super.getAdapter(adapter);
    }

    public boolean isChildCommentColumnAvailable(@NotNull DBRProgressMonitor monitor) {
        // child_object is very helpful column in v_catalog.comments table, but it's not childObjectColumnAvailable in Vertica versions < 9.3 and in some other cases
        if (childObjectColumnAvailable == null) {
            try {
                try (JDBCSession session = DBUtils.openMetaSession(monitor, this, "Check child comment column existence")) {
                    try (final JDBCPreparedStatement dbStat = session.prepareStatement(
                        "SELECT child_object FROM v_catalog.comments WHERE 1<>1")) {
                        dbStat.setFetchSize(1);
                        dbStat.execute();
                        childObjectColumnAvailable = true;
                    }
                }
            } catch (Exception e) {
                childObjectColumnAvailable = false;
            }
        }
        return childObjectColumnAvailable;
    }


    class NodeCache extends JDBCObjectCache<VerticaDataSource, VerticaNode> {
        @NotNull
        @Override
        protected JDBCStatement prepareObjectsStatement(@NotNull JDBCSession session, @NotNull VerticaDataSource mySQLTable) throws SQLException
        {
            return session.prepareStatement(
                "SELECT * FROM v_catalog.nodes ORDER BY nodE_name");
        }

        @Override
        protected VerticaNode fetchObject(@NotNull JDBCSession session, @NotNull VerticaDataSource dataSource, @NotNull JDBCResultSet dbResult) throws SQLException, DBException
        {
            return new VerticaNode(dataSource, dbResult);
        }

    }

}
