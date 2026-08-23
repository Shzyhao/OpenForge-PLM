import { Navigate, Route, Routes } from 'react-router-dom'
import Login from './pages/Login'
import Home from './pages/Home'
import MaterialPage from './pages/MaterialPage'
import BomPage from './pages/BomPage'
import DocPage from './pages/DocPage'
import MyTasksPage from './pages/MyTasksPage'
import WorkflowDefsPage from './pages/WorkflowDefsPage'
import Placeholder from './pages/Placeholder'
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
        <Route path="change" element={<Placeholder title="变更管理（M3-3）" />} />
        <Route path="project" element={<Placeholder title="项目管理（M6）" />} />
        <Route path="knowledge" element={<Placeholder title="知识库（M5）" />} />
        <Route path="admin" element={<Placeholder title="管理后台（按需开放）" />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
