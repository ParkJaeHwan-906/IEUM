import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { messageOf } from '../api/errors'
import { USE_MOCK } from '../api/config'

export default function LoginPage() {
  const { login, demoLogin } = useAuth()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setSubmitting(true)
    setError(null)
    try {
      await login({ email, password })
    } catch (err) {
      setError(messageOf(err))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form className="form" onSubmit={submit}>
      <img src="/logo-symbol.png" alt="이음" style={{ width: 72, margin: '8px auto 4px' }} />
      <h1 style={{ textAlign: 'center' }}>로그인</h1>
      <p className="sub" style={{ textAlign: 'center' }}>
        이메일로 가입한 계정으로 로그인합니다
      </p>
      <div className="field">
        <label htmlFor="email">이메일</label>
        <input id="email" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
      </div>
      <div className="field">
        <label htmlFor="password">비밀번호</label>
        <input id="password" type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
      </div>
      {error && <div className="alert alert--error">{error}</div>}
      <button type="submit" className="btn btn--teal btn--block" disabled={submitting}>
        {submitting ? '로그인 중...' : '로그인'}
      </button>
      <p className="sub" style={{ textAlign: 'center' }}>
        아직 계정이 없나요? <Link to="/signup" style={{ color: 'var(--teal-700)', fontWeight: 700 }}>회원가입</Link>
      </p>
      {USE_MOCK && (
        <>
          <div className="divider" />
          <p className="sub" style={{ textAlign: 'center', marginBottom: 0 }}>
            인증 서버 없이 화면만 둘러보려면
          </p>
          <div className="segmented">
            <button
              type="button"
              className="btn btn--ghost"
              onClick={() => demoLogin('CONSUMER')}
            >
              데모 소비자
            </button>
            <button
              type="button"
              className="btn btn--ghost"
              onClick={() => demoLogin('BUSINESS_OWNER')}
            >
              데모 점주
            </button>
          </div>
        </>
      )}
    </form>
  )
}
