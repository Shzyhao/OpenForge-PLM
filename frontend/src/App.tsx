import { Navigate, Route, Routes } from 'react-router-dom'
import Login from './pages/Login'
import Home from './pages/Home'
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
        <Route path="material" element={<Placeholder title="物料 / BOM（M2）" />} />
        <Route path="doc" element={<Placeholder title="文档管理（M2）" />} />
        <Route path="change" element={<Placeholder title="变更管理（M3）" />} />
        <Route path="project" element={<Placeholder title="项目管理（M6）" />} />
        <Route path="knowledge" element={<Placeholder title="知识库（M5）" />} />
        <Route path="admin" element={<Placeholder title="管理后台（按需开放）" />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
