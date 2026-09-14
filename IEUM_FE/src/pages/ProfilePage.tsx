import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'

export default function ProfilePage() {
  const { user, isOwner, logout } = useAuth()
  const navigate = useNavigate()
  if (!user) return null

  const expires = new Date(user.exp * 1000)

  return (
    <div className="form">
      <h1>내 정보</h1>
      <div className="card" style={{ padding: 16 }}>
        <dl className="kv" style={{ marginTop: 0 }}>
          <div>
            <dt>닉네임</dt>
            <dd>{user.nickname}</dd>
          </div>
          <div>
            <dt>계정 유형</dt>
            <dd>{isOwner ? '점주' : '소비자'}</dd>
          </div>
          <div style={{ gridColumn: '1 / -1' }}>
            <dt>사용자 ID (uid)</dt>
            <dd style={{ fontSize: 12, wordBreak: 'break-all', fontFamily: 'monospace' }}>{user.uid}</dd>
          </div>
          <div style={{ gridColumn: '1 / -1' }}>
            <dt>Access Token 만료</dt>
            <dd>{expires.toLocaleString('ko-KR')}</dd>
          </div>
        </dl>
      </div>
      <div className="stack">
        {isOwner ? (
          <Link to="/owner" className="btn btn--teal btn--block">
            점주 센터로 이동
          </Link>
        ) : (
          <Link to="/orders" className="btn btn--teal btn--block">
            내 예약 보기
          </Link>
        )}
        <button
          type="button"
          className="btn btn--ghost btn--block"
          onClick={async () => {
            await logout()
            navigate('/')
          }}
        >
          로그아웃
        </button>
      </div>
    </div>
  )
}
