import { BrowserRouter, Navigate, Route, Routes, useLocation, useParams } from 'react-router-dom'
import { AuthProvider, useAuth } from './auth/AuthContext'
import Layout from './components/Layout'
import HomePage from './pages/HomePage'
import StoresPage from './pages/StoresPage'
import StoreDetailPage from './pages/StoreDetailPage'
import ItemDetailPage from './pages/ItemDetailPage'
import OrdersPage from './pages/OrdersPage'
import LoginPage from './pages/LoginPage'
import SignupPage from './pages/SignupPage'
import ProfilePage from './pages/ProfilePage'
import OwnerPage from './pages/OwnerPage'
import { Empty } from './components/ui'
import { Link } from 'react-router-dom'
import type { ReactNode } from 'react'

function RequireAuth({ children, owner = false }: { children: ReactNode; owner?: boolean }) {
  const { user, isOwner } = useAuth()
  const location = useLocation()
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname }} />
  if (owner && !isOwner) {
    return (
      <Empty icon="🔒" title="점주 계정만 접근할 수 있습니다">
        <Link to="/" className="btn btn--ghost btn--sm">
          홈으로
        </Link>
      </Empty>
    )
  }
  return <>{children}</>
}

function ItemDetailRoute() {
  const { itemUid } = useParams()
  return <ItemDetailPage key={itemUid} />
}

function GuestOnly({ children }: { children: ReactNode }) {
  const { user, isOwner } = useAuth()
  const location = useLocation()
  const from = (location.state as { from?: string } | null)?.from
  if (user) return <Navigate to={isOwner ? '/owner' : (from ?? '/')} replace />
  return <>{children}</>
}

export default function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          <Route element={<Layout />}>
            <Route index element={<HomePage />} />
            <Route path="stores" element={<StoresPage />} />
            <Route path="stores/:storeUid" element={<StoreDetailPage />} />
            <Route path="items/:itemUid" element={<ItemDetailRoute />} />
            <Route
              path="orders"
              element={
                <RequireAuth>
                  <OrdersPage />
                </RequireAuth>
              }
            />
            <Route
              path="owner"
              element={
                <RequireAuth owner>
                  <OwnerPage />
                </RequireAuth>
              }
            />
            <Route
              path="me"
              element={
                <RequireAuth>
                  <ProfilePage />
                </RequireAuth>
              }
            />
            <Route
              path="login"
              element={
                <GuestOnly>
                  <LoginPage />
                </GuestOnly>
              }
            />
            <Route
              path="signup"
              element={
                <GuestOnly>
                  <SignupPage />
                </GuestOnly>
              }
            />
            <Route
              path="*"
              element={
                <Empty icon="🧭" title="페이지를 찾을 수 없어요">
                  <Link to="/" className="btn btn--ghost btn--sm">
                    홈으로
                  </Link>
                </Empty>
              }
            />
          </Route>
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  )
}
