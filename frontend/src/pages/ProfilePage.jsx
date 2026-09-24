import { useState } from 'react'
import { Link } from 'react-router-dom'
import { getProfile, updateProfile } from '../api/auth'
import { listCategories } from '../api/products'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'
import { Alert, Badge, EmptyState, ErrorState, Field, Skeleton } from '../components/ui'
import { useApiQuery } from '../hooks/useApi'
import { formatDate } from '../lib/format'

/**
 * The signed-in user's profile.
 *
 * Reads through the API rather than from AuthContext, so the page shows the current server
 * state rather than what the token implied at sign-in. The two can legitimately differ: a
 * role change made by an administrator takes effect immediately on the server, and a page
 * rendering the stale claim would be quietly wrong.
 */
export function ProfilePage() {
  const { user, refreshUser } = useAuth()
  const toast = useToast()

  const profile = useApiQuery((signal) => getProfile({ signal }), [])

  const [editing, setEditing] = useState(false)
  const [form, setForm] = useState(null)
  const [errors, setErrors] = useState({})
  const [saving, setSaving] = useState(false)

  const data = profile.data ?? user

  const startEditing = () => {
    setForm({
      name: data?.name ?? '',
      phone: data?.phone ?? '',
      address: data?.address ?? '',
    })
    setErrors({})
    setEditing(true)
  }

  const cancelEditing = () => {
    setEditing(false)
    setErrors({})
  }

  const handleSubmit = async (event) => {
    event.preventDefault()
    setSaving(true)
    setErrors({})

    try {
      /*
       * Empty strings are converted to undefined so an omitted field means "unchanged". The
       * backend's UpdateProfileRequest treats null as "leave alone", so sending "" for a
       * cleared phone would try to store an empty string and trip @Pattern on the server.
       */
      await updateProfile({
        name: form.name.trim() || undefined,
        phone: form.phone.trim() || undefined,
        address: form.address.trim() || undefined,
      })

      // Re-read both so the header and this page agree immediately.
      await Promise.all([profile.refetch(), refreshUser()])
      toast.success('Your profile has been updated')
      setEditing(false)
    } catch (cause) {
      if (cause.isValidation) {
        setErrors({
          name: cause.for('name'),
          phone: cause.for('phone'),
          address: cause.for('address'),
          form: cause.for('name') || cause.for('phone') || cause.for('address') ? null : cause.message,
        })
      } else {
        setErrors({ form: cause.message })
      }
    } finally {
      setSaving(false)
    }
  }

  if (profile.error) {
    return (
      <div className="page">
        <div className="container container--narrow">
          <ErrorState error={profile.error} title="Could not load your profile" onRetry={() => profile.refetch()} />
        </div>
      </div>
    )
  }

  if (profile.loading && !data) {
    return (
      <div className="page">
        <div className="container container--narrow">
          <Skeleton height="200px" radius="14px" />
          <Skeleton height="260px" radius="14px" />
        </div>
      </div>
    )
  }

  return (
    <div className="page">
      <div className="container container--narrow">
        <header className="page__head">
          <h1 className="page__title">Your profile</h1>
          {!editing && (
            <button type="button" className="btn btn--secondary btn--sm" onClick={startEditing}>
              Edit profile
            </button>
          )}
        </header>

        <section className="card">
          <div className="profile-head">
            <div className="profile-avatar" aria-hidden="true">
              {data?.name?.charAt(0)?.toUpperCase() ?? '?'}
            </div>
            <div>
              <h2 className="profile-name">{data?.name}</h2>
              <p className="profile-email">{data?.email}</p>
              <div className="profile-badges">
                <Badge tone={data?.role === 'ADMIN' ? 'info' : 'neutral'}>
                  {data?.role === 'ADMIN' ? 'Administrator' : 'Customer'}
                </Badge>
                {/*
                 * The auth provider is shown because it changes what the account can do - a
                 * Google account has no password, so a "change password" control would be
                 * meaningless and is absent for exactly that reason.
                 */}
                <Badge tone="neutral">
                  {data?.provider === 'GOOGLE' ? 'Signed in with Google' : 'Email and password'}
                </Badge>
              </div>
            </div>
          </div>

          <dl className="detail__facts">
            <div>
              <dt>Member since</dt>
              <dd>{formatDate(data?.createdAt)}</dd>
            </div>
            <div>
              <dt>Phone</dt>
              <dd>{data?.phone || 'Not provided'}</dd>
            </div>
            <div className="detail__fact--full">
              <dt>Default address</dt>
              <dd>{data?.address || 'Not provided'}</dd>
            </div>
          </dl>
        </section>

        {editing && (
          <section className="card">
            <h2 className="card__title">Edit details</h2>

            {errors.form && <Alert tone="error">{errors.form}</Alert>}

            <form onSubmit={handleSubmit} noValidate>
              <Field
                id="name"
                label="Full name"
                value={form.name}
                onChange={(event) => setForm((current) => ({ ...current, name: event.target.value }))}
                error={errors.name}
                hint="Between 2 and 60 characters."
              />

              <Field
                id="phone"
                label="Phone"
                value={form.phone}
                onChange={(event) => setForm((current) => ({ ...current, phone: event.target.value }))}
                error={errors.phone}
                hint="Digits, spaces and + ( ) - only. Leave blank to remove."
              />

              <Field
                id="address"
                label="Default shipping address"
                as="textarea"
                rows={3}
                value={form.address}
                onChange={(event) => setForm((current) => ({ ...current, address: event.target.value }))}
                error={errors.address}
                hint="Prefilled at checkout. Use 'Save as address' there to update this instead."
              />

              <div className="form-actions">
                <button type="submit" className="btn btn--primary" disabled={saving}>
                  {saving ? 'Saving…' : 'Save changes'}
                </button>
                <button type="button" className="btn btn--ghost" onClick={cancelEditing} disabled={saving}>
                  Cancel
                </button>
              </div>
            </form>
          </section>
        )}

        {/*
         * A note about what this page deliberately cannot do. There is no "delete account"
         * and no "change password" because the backend exposes neither endpoint - and a
         * button that cannot work is worse than no button. Stating the omission is more
         * useful than leaving a reader to wonder whether they missed it.
         */}
        <p className="order-note order-note--muted">
          Password changes and account deletion are not exposed by this API. An administrator
          can change your role from the admin console.
        </p>
      </div>
    </div>
  )
}

/**
 * Categories, as a browsable index.
 *
 * Each links into the catalogue with `categoryId` already applied, which is what the filter's
 * URL-driven state is for - the link is a deep link into a filtered list, and the catalogue
 * picks it up with no extra wiring.
 */
export function CategoriesPage() {
  const categories = useApiQuery((signal) => listCategories({ signal }), [])
  const result = categories.data

  return (
    <div className="page">
      <div className="container">
        <header className="page__head">
          <div>
            <h1 className="page__title">Categories</h1>
            <p className="page__subtitle">Browse the catalogue by department.</p>
          </div>
        </header>

        {categories.error ? (
          <ErrorState error={categories.error} title="Could not load categories" onRetry={() => categories.refetch()} />
        ) : categories.loading && !result ? (
          <div className="category-grid">
            {[0, 1, 2, 3, 4, 5].map((index) => (
              <Skeleton key={index} height="150px" radius="12px" />
            ))}
          </div>
        ) : result?.length ? (
          <div className="category-grid">
            {result.map((category) => (
              <Link key={category.id} to={`/products?categoryId=${category.id}`} className="category-card">
                <div className="category-card__media">
                  {category.imageUrl ? (
                    <img src={category.imageUrl} alt="" loading="lazy" />
                  ) : (
                    <span className="category-card__initial" aria-hidden="true">
                      {category.name?.charAt(0) ?? '?'}
                    </span>
                  )}
                </div>

                <div className="category-card__body">
                  <h2 className="category-card__name">{category.name}</h2>
                  <p className="category-card__count">
                    {category.productCount} {category.productCount === 1 ? 'product' : 'products'}
                  </p>
                  {category.description && (
                    <p className="category-card__description">{category.description}</p>
                  )}
                </div>
              </Link>
            ))}
          </div>
        ) : (
          <EmptyState title="No categories yet" message="Categories will appear here once an administrator creates them." />
        )}
      </div>
    </div>
  )
}
