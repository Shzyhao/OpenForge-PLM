import { Navigate, Route, Routes } from 'react-router-dom'
import Login from './pages/Login'
import Home from './pages/Home'
import MaterialPage from './pages/MaterialPage'
import BomPage from './pages/BomPage'
import DocPage from './pages/DocPage'
import MyTasksPage from './pages/MyTasksPage'
import WorkflowDefsPage from './pages/WorkflowDefsPage'
import ChangePage from './pages/ChangePage'
import KnowledgePage from './pages/KnowledgePage'
import ProjectPage from './pages/ProjectPage'
import UserAdminPage from './pages/UserAdminPage'
import RoleAdminPage from './pages/RoleAdminPage'
import SecurityLogPage from './pages/SecurityLogPage'
import MetaObjectsPage from './pages/MetaObjectsPage'
import ObjectDataPage from './pages/ObjectDataPage'
import ModuleAdminPage from './pages/ModuleAdminPage'
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
        <Route path="tasks" element={<MyTasksPage />} />
        <Route path="workflow" element={<WorkflowDefsPage />} />
        <Route path="change" element={<ChangePage />} />
        <Route path="project" element={<ProjectPage />} />
        <Route path="knowledge" element={<KnowledgePage />} />
        <Route path="meta/objects" element={<MetaObjectsPage />} />
        <Route path="meta/data" element={<ObjectDataPage />} />
        <Route path="system/users" element={<UserAdminPage />} />
        <Route path="system/roles" element={<RoleAdminPage />} />
        <Route path="system/logs" element={<SecurityLogPage />} />
        <Route path="system/modules" element={<ModuleAdminPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
