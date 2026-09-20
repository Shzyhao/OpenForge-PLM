import { Navigate, Route, Routes } from 'react-router-dom'
import Login from './pages/Login'
import Home from './pages/Home'
import MaterialPage from './pages/MaterialPage'
import BomPage from './pages/BomPage'
import DocPage from './pages/DocPage'
import DrawingPage from './pages/DrawingPage'
import MyTasksPage from './pages/MyTasksPage'
import WorkflowDefsPage from './pages/WorkflowDefsPage'
import DelegatesPage from './pages/DelegatesPage'
import NotificationsPage from './pages/NotificationsPage'
import RecycleBinPage from './pages/RecycleBinPage'
import ChangePage from './pages/ChangePage'
import KnowledgePage from './pages/KnowledgePage'
import ProjectPage from './pages/ProjectPage'
import UserAdminPage from './pages/UserAdminPage'
import RoleAdminPage from './pages/RoleAdminPage'
import SecurityLogPage from './pages/SecurityLogPage'
import MetaObjectsPage from './pages/MetaObjectsPage'
import ObjectDataPage from './pages/ObjectDataPage'
import LayoutDesignerPage from './pages/LayoutDesignerPage'
import IntegrationPage from './pages/IntegrationPage'
import ModuleAdminPage from './pages/ModuleAdminPage'
import OrgAdminPage from './pages/OrgAdminPage'
import TenantAdminPage from './pages/TenantAdminPage'
import NumberRuleAdminPage from './pages/NumberRuleAdminPage'
import AppLayout from './layouts/AppLayout'
import { getToken } from './api/client'

function RequireAuth({ children }: { children: React.ReactElement }) {
  return getToken() ? children : <Navigate to="/login" replace />
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route
        path="/"
        element={
          <RequireAuth>
            <AppLayout />
          </RequireAuth>
        }
      >
        <Route index element={<Home />} />
        <Route path="material" element={<MaterialPage />} />
        <Route path="bom" element={<BomPage />} />
        <Route path="doc" element={<DocPage />} />
        <Route path="drawing" element={<DrawingPage />} />
        <Route path="tasks" element={<MyTasksPage />} />
        <Route path="workflow" element={<WorkflowDefsPage />} />
        <Route path="workflow/delegates" element={<DelegatesPage />} />
        <Route path="notifications" element={<NotificationsPage />} />
        <Route path="recycle" element={<RecycleBinPage />} />
        <Route path="change" element={<ChangePage />} />
        <Route path="project" element={<ProjectPage />} />
        <Route path="knowledge" element={<KnowledgePage />} />
        <Route path="meta/objects" element={<MetaObjectsPage />} />
        <Route path="meta/data" element={<ObjectDataPage />} />
        <Route path="meta/designer" element={<LayoutDesignerPage />} />
        <Route path="integration" element={<IntegrationPage />} />
        <Route path="system/users" element={<UserAdminPage />} />
        <Route path="system/roles" element={<RoleAdminPage />} />
        <Route path="system/logs" element={<SecurityLogPage />} />
        <Route path="system/modules" element={<ModuleAdminPage />} />
        <Route path="system/orgs" element={<OrgAdminPage />} />
        <Route path="system/tenants" element={<TenantAdminPage />} />
        <Route path="system/numbers" element={<NumberRuleAdminPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
