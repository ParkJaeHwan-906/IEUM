import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { signup } from '../api/auth'
import { messageOf } from '../api/errors'
import type { SignupRequest } from '../types/api'

const initial: SignupRequest = {
  name: '',
  tel: '',
  email: '',
  nickname: '',
  password: '',
  userType: 'CONSUMER',
}

function validate(form: SignupRequest, confirm: string): Partial<Record<keyof SignupRequest | 'confirm', string>> {
  const errors: Partial<Record<keyof SignupRequest | 'confirm', string>> = {}
  if (!form.name || form.name.length > 10) errors.name = '이름은 1~10자로 입력해 주세요.'
  if (!/^01[016789]\d{7,8}$/.test(form.tel)) errors.tel = '하이픈 없이 숫자만 입력해 주세요 (예: 01012345678).'
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email) || form.email.length > 100) errors.email = '올바른 이메일 형식이 아닙니다.'
  if (form.nickname.length < 2 || form.nickname.length > 20) errors.nickname = '닉네임은 2~20자로 입력해 주세요.'
  if (form.password.length < 8 || form.password.length > 72) errors.password = '비밀번호는 8~72자로 입력해 주세요.'
  if (confirm !== form.password) errors.confirm = '비밀번호가 일치하지 않습니다.'
  return errors
}

export default function SignupPage() {
  const navigate = useNavigate()
  const [form, setForm] = useState<SignupRequest>(initial)
  const [confirm, setConfirm] = useState('')
  const [touched, setTouched] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [done, setDone] = useState(false)

  const errors = touched ? validate(form, confirm) : {}

  function update<K extends keyof SignupRequest>(key: K, value: SignupRequest[K]) {
    setForm((f) => ({ ...f, [key]: value }))
  }

  async function submit(e: FormEvent) {
    e.preventDefault()
    setTouched(true)
    if (Object.keys(validate(form, confirm)).length > 0) return
    setSubmitting(true)
    setError(null)
    try {
      await signup(form)
      setDone(true)
      setTimeout(() => navigate('/login', { replace: true }), 1200)
    } catch (err) {
      setError(messageOf(err))
    } finally {
      setSubmitting(false)
    }
  }

  if (done) {
    return (
      <div className="form">
        <div className="alert alert--success">회원가입이 완료되었습니다. 로그인 페이지로 이동합니다.</div>
      </div>
    )
  }

  return (
    <form className="form" onSubmit={submit} noValidate>
      <h1>회원가입</h1>
      <p className="sub">소비자는 마감 상품을 예약하고, 점주는 매장과 상품을 등록합니다.</p>

      <div className="field">
        <label>가입 유형</label>
        <div className="segmented">
          <input type="radio" id="type-consumer" name="userType" checked={form.userType === 'CONSUMER'} onChange={() => update('userType', 'CONSUMER')} />
          <label htmlFor="type-consumer">🛍️ 소비자</label>
          <input type="radio" id="type-owner" name="userType" checked={form.userType === 'BUSINESS_OWNER'} onChange={() => update('userType', 'BUSINESS_OWNER')} />
          <label htmlFor="type-owner">🏪 점주</label>
        </div>
      </div>

      <div className="field">
        <label htmlFor="name">이름</label>
        <input id="name" maxLength={10} value={form.name} onChange={(e) => update('name', e.target.value)} />
        {errors.name && <span className="error">{errors.name}</span>}
      </div>
      <div className="field">
        <label htmlFor="tel">휴대전화</label>
        <input id="tel" inputMode="numeric" placeholder="01012345678" value={form.tel} onChange={(e) => update('tel', e.target.value.replace(/\D/g, ''))} />
        {errors.tel && <span className="error">{errors.tel}</span>}
      </div>
      <div className="field">
        <label htmlFor="email">이메일</label>
        <input id="email" type="email" autoComplete="email" maxLength={100} value={form.email} onChange={(e) => update('email', e.target.value)} />
        <span className="hint">로그인 아이디로 사용됩니다.</span>
        {errors.email && <span className="error">{errors.email}</span>}
      </div>
      <div className="field">
        <label htmlFor="nickname">닉네임</label>
        <input id="nickname" maxLength={20} value={form.nickname} onChange={(e) => update('nickname', e.target.value)} />
        {errors.nickname && <span className="error">{errors.nickname}</span>}
      </div>
      <div className="field">
        <label htmlFor="password">비밀번호</label>
        <input id="password" type="password" autoComplete="new-password" maxLength={72} value={form.password} onChange={(e) => update('password', e.target.value)} />
        <span className="hint">8자 이상 72자 이하</span>
        {errors.password && <span className="error">{errors.password}</span>}
      </div>
      <div className="field">
        <label htmlFor="confirm">비밀번호 확인</label>
        <input id="confirm" type="password" autoComplete="new-password" value={confirm} onChange={(e) => setConfirm(e.target.value)} />
        {errors.confirm && <span className="error">{errors.confirm}</span>}
      </div>

      {error && <div className="alert alert--error">{error}</div>}
      <button type="submit" className="btn btn--teal btn--block" disabled={submitting}>
        {submitting ? '가입 중...' : '가입하기'}
      </button>
      <p className="sub" style={{ textAlign: 'center' }}>
        이미 계정이 있나요? <Link to="/login" style={{ color: 'var(--teal-700)', fontWeight: 700 }}>로그인</Link>
      </p>
    </form>
  )
}
