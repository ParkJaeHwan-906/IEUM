import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { USE_MOCK } from '../api/config'
import { resetMock } from '../mocks/db'

function navClass({ isActive }: { isActive: boolean }) {
  return isActive ? 'active' : ''
}

export default function Layout() {
  const { user, isOwner, logout } = useAuth()
  const navigate = useNavigate()

  async function handleLogout() {
    await logout()
    navigate('/')
  }

  return (
    <div className="app">
      {USE_MOCK && (
        <div className="mock-banner">
          매장·상품·예약은 더미 데이터입니다 (인증 API만 실제 서버 연동).
          <button
            type="button"
            onClick={() => {
              resetMock()
              window.location.reload()
            }}
          >
            더미 초기화
          </button>
        </div>
      )}
      <header className="header">
        <div className="container header__inner">
          <NavLink to="/" className="brand">
            <img src="/logo-symbol.png" alt="" />
            이음
            <small>소상공인과 소비자를 잇다</small>
          </NavLink>
          <nav className="nav">
            <NavLink to="/" end className={navClass}>
              홈
            </NavLink>
            <NavLink to="/stores" className={navClass}>
              매장
            </NavLink>
            {!isOwner && (
              <NavLink to="/orders" className={navClass}>
                내 예약
              </NavLink>
            )}
            {isOwner && (
              <NavLink to="/owner" className={navClass}>
                점주 센터
              </NavLink>
            )}
          </nav>
          <div className="header__right">
            {user ? (
              <>
                <span className="user-chip">
                  <span className={`role ${isOwner ? 'owner' : ''}`}>{isOwner ? '점주' : '소비자'}</span>
                  {user.nickname}
                </span>
                <button type="button" className="btn btn--ghost btn--sm" onClick={handleLogout}>
                  로그아웃
                </button>
              </>
            ) : (
              <>
                <NavLink to="/login" className="btn btn--ghost btn--sm">
                  로그인
                </NavLink>
                <NavLink to="/signup" className="btn btn--teal btn--sm">
                  회원가입
                </NavLink>
              </>
            )}
          </div>
        </div>
      </header>

      <main className="main">
        <div className="container">
          <Outlet />
        </div>
      </main>

      <footer className="footer container">© 2026 IEUM · 마감 상품 예약으로 음식물 폐기를 줄입니다</footer>

      <nav className="bottom-nav">
        <NavLink to="/" end className={navClass}>
          <span className="icon">🏠</span>홈
        </NavLink>
        <NavLink to="/stores" className={navClass}>
          <span className="icon">🏪</span>매장
        </NavLink>
        {isOwner ? (
          <NavLink to="/owner" className={navClass}>
            <span className="icon">📋</span>점주 센터
          </NavLink>
        ) : (
          <NavLink to="/orders" className={navClass}>
            <span className="icon">🧾</span>내 예약
          </NavLink>
        )}
        <NavLink to={user ? '/me' : '/login'} className={navClass}>
          <span className="icon">👤</span>
          {user ? '내 정보' : '로그인'}
        </NavLink>
      </nav>
    </div>
  )
}
