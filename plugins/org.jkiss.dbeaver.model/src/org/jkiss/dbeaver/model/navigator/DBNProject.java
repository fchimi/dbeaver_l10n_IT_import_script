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
package org.jkiss.dbeaver.model.navigator;

import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.CoreException;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ModelPreferences;
import org.jkiss.dbeaver.model.DBIcon;
import org.jkiss.dbeaver.model.DBPImage;
import org.jkiss.dbeaver.model.app.*;
import org.jkiss.dbeaver.model.navigator.registry.DBNRegistry;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.utils.ArrayUtils;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * DBNProject
 */
public class DBNProject extends DBNResource implements DBNNodeExtendable {
    private static final Log log = Log.getLog(DBNProject.class);

    private final DBPProject project;
    private final List<DBNNode> extraNodes = new ArrayList<>();

    public DBNProject(DBNNode parentNode, DBPProject project, DBPResourceHandler handler) {
        super(parentNode, project.getEclipseProject(), handler);
        this.project = project;
        DBNRegistry.getInstance().extendNode(this, false);
    }

    @NotNull
    public DBPProject getProject() {
        return project;
    }

    public DBNProjectDatabases getDatabases() {
        try {
            for (DBNNode db : getChildren(new VoidProgressMonitor())) {
                if (db instanceof DBNProjectDatabases) {
                    return (DBNProjectDatabases) db;
                }
            }
        } catch (DBException e) {
            throw new IllegalStateException("Can't read project contents", e);
        }
        throw new IllegalStateException("No databases resource in project");
    }

    @Override
    public String getNodeName() {
        return project.getName();
    }

    protected String getResourceNodeType() {
        return "project";
    }

    @Override
    public String getNodeDescription() {
        if (project.isVirtual()) {
            return null;
        }
        project.ensureOpen();
        try {
            return project.getEclipseProject().getDescription().getComment();
        } catch (CoreException e) {
            log.debug(e);
            return null;
        }
    }

    @NotNull
    @Override
    public String getName() {
        return project.getId();
    }

    @Override
    public String getLocalizedName(String locale) {
        return getNodeName();
    }

    @Override
    public DBPImage getNodeIcon() {
        return DBIcon.PROJECT;
    }

    @Override
    public boolean allowsOpen() {
        return true;
    }

    @Override
    public <T> T getAdapter(Class<T> adapter) {
        if (adapter == DBNProject.class) {
            return adapter.cast(this);
        }
        return super.getAdapter(adapter);
    }

    @Override
    public DBPProject getOwnerProject() {
        return project;
    }

    @Override
    public Throwable getLastLoadError() {
        return getProject().getDataSourceRegistry().getLastError();
    }

    @Override
    public boolean supportsRename() {
        return !project.isVirtual();
    }

    @Override
    public void rename(DBRProgressMonitor monitor, String newName) throws DBException {
        project.ensureOpen();

        try {
            final IProjectDescription description = project.getEclipseProject().getDescription();
            description.setName(newName);
            project.getEclipseProject().move(description, true, monitor.getNestedMonitor());
        } catch (CoreException e) {
            throw new DBException("Can't rename project", e);
        }
    }

    @Override
    public DBNNode[] getChildren(DBRProgressMonitor monitor) throws DBException {
        project.ensureOpen();

        if (project.getEclipseProject() != null && !project.getEclipseProject().isOpen()) {
            return new DBNNode[0];
        }
        List<DBNNode> childrenFiltered = new ArrayList<>();
        Collections.addAll(childrenFiltered, super.getChildren(monitor));
        if (!DBWorkbench.getPlatform().getPreferenceStore().getBoolean(ModelPreferences.NAVIGATOR_SHOW_FOLDER_PLACEHOLDERS)) {
            // Remove non-existing resources (placeholders)
            childrenFiltered.removeIf(node ->
                node instanceof DBNResource && !((DBNResource) node).isResourceExists());
        }
        if (!extraNodes.isEmpty()) {
            childrenFiltered.addAll(extraNodes);
        }
        return childrenFiltered.toArray(new DBNNode[0]);
    }

    @Override
    protected DBNNode[] readChildNodes(DBRProgressMonitor monitor) throws DBException {
        DBNModel model = getModel();
        if (model.isGlobal() && !project.isOpen()) {
            project.ensureOpen();
        }
        DBNNode[] children = super.readChildNodes(monitor);

        children = ArrayUtils.insertArea(DBNNode.class, children, 0, new Object[]{
            new DBNProjectDatabases(this, project.getDataSourceRegistry())});

        return children;
    }

    @Override
    protected IResource[] addImplicitMembers(IResource[] members) {
        DBPWorkspace workspace = project.getWorkspace();
        if (workspace instanceof DBPWorkspaceEclipse) {
            for (DBPResourceHandlerDescriptor rh : ((DBPWorkspaceEclipse)workspace).getAllResourceHandlers()) {
                IFolder rhDefaultRoot = ((DBPWorkspaceEclipse)workspace).getResourceDefaultRoot(getProject(), rh, false);
                if (rhDefaultRoot != null && !rhDefaultRoot.exists()) {
                    // Add as explicit member
                    members = ArrayUtils.add(IResource.class, members, rhDefaultRoot);
                }
            }
        }
        return super.addImplicitMembers(members);
    }

    @Override
    public DBNNode refreshNode(DBRProgressMonitor monitor, Object source) throws DBException {
        project.getDataSourceRegistry().refreshConfig();
        super.refreshThisResource(monitor);
        return this;
    }

    public DBNResource findResource(IResource resource) {
        List<IResource> path = new ArrayList<>();
        for (IResource parent = resource;
             !(parent instanceof IProject) && !CommonUtils.equalObjects(parent, project.getRootResource());
             parent = parent.getParent())
        {
            path.add(0, parent);
        }

        DBNResource resNode = this;
        for (IResource res : path) {
            try {
                resNode.getChildren(new VoidProgressMonitor());
            } catch (DBException e) {
                log.error(e);
            }
            resNode = resNode.getChild(res);
            if (resNode == null) {
                return null;
            }
        }
        return resNode;
    }

    @Override
    protected void handleChildResourceChange(IResourceDelta delta) {
        if (CommonUtils.equalObjects(delta.getResource(), project.getRootResource())) {
            // Go inside root resource
            for (IResourceDelta cChild : delta.getAffectedChildren()) {
                handleChildResourceChange(cChild);
            }
            return;
        }
        final String name = delta.getResource().getName();
        if (name.equals(DBPProject.METADATA_FOLDER)) {
            // Metadata configuration changed
            IResourceDelta[] configFiles = delta.getAffectedChildren();
            boolean dsChanged = false;
            if (configFiles != null) {
                for (IResourceDelta rd : configFiles) {
                    IResource childRes = rd.getResource();
                    if (childRes instanceof IFile && childRes.getName().startsWith(DBPDataSourceRegistry.MODERN_CONFIG_FILE_PREFIX)) {
                        dsChanged = true;
                    }
                }
            }
            if (dsChanged) {
                getDatabases().getDataSourceRegistry().refreshConfig();
            }
        } else {
            super.handleChildResourceChange(delta);
        }
    }

    @NotNull
    @Override
    public List<DBNNode> getExtraNodes() {
        return extraNodes;
    }

    public <T> T getExtraNode(Class<T> nodeType) {
        if (extraNodes != null) {
            for (DBNNode node : extraNodes) {
                if (nodeType.isAssignableFrom(node.getClass())) {
                    return nodeType.cast(node);
                }
            }
        }
        return null;
    }

    @Override
    public void addExtraNode(@NotNull DBNNode node, boolean reflect) {
        extraNodes.add(node);
        extraNodes.sort(Comparator.comparing(DBNNode::getNodeName));
        if (reflect) {
            getModel().fireNodeEvent(new DBNEvent(this, DBNEvent.Action.ADD, node));
        }
    }

    @Override
    public void removeExtraNode(@NotNull DBNNode node) {
        if (extraNodes.remove(node)) {
            getModel().fireNodeEvent(new DBNEvent(this, DBNEvent.Action.REMOVE, node));
        }
    }

    @Override
    protected IResource getContentLocationResource() {
        return project.getRootResource();
    }

    @Override
    protected void dispose(boolean reflect) {
        for (DBNNode node : extraNodes) {
            node.dispose(reflect);
        }
        extraNodes.clear();
        super.dispose(reflect);
    }

    @Override
    public String getNodeItemPath() {
        return NodePathType.resource.getPrefix() + project.getId();
    }

    @Override
    public boolean hasChildren(boolean navigableOnly) {
        return true;
    }
}
