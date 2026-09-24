import { Suspense, lazy, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { getProduct, listProducts } from '../api/products'
import { useCart } from '../context/CartContext'
import { useToast } from '../context/ToastContext'
import { ProductCard } from '../components/ProductCard'
import { Badge, ErrorState, Skeleton, StockBadge } from '../components/ui'
import { useApiQuery } from '../hooks/useApi'
import { formatMoney } from '../lib/format'

/*
 * The 3D viewer is loaded on demand, not imported.
 *
 * This is the difference between the promise and the practice. A static import of a module
 * that reaches three.js puts three.js in the main bundle graph, so every visitor - including
 * one who only ever looks at the home page - downloads and parses ~900 kB to render nothing
 * three-dimensional. `lazy` makes it a separate chunk that is fetched the first time a
 * product page actually renders the viewer.
 *
 * Measured: with the static import the main bundle chain was 1.1 MB; with this split the
 * initial payload is ~247 kB and the three.js chunk is only requested when needed.
 */
const ProductViewer3D = lazy(() =>
  import('../components/three/ProductViewer3D').then((module) => ({
    default: module.ProductViewer3D,
  })),
)

/**
 * Product detail: image and 3D viewer, description, and add-to-cart.
 *
 * <h2>Why the product is re-fetched rather than read from the list</h2>
 *
 * The list response and the detail response are the same shape here, so it is tempting to
 * pass the product through router state. That breaks on the two paths that matter most:
 * a shared link (there is no previous page to have come from) and a refresh. Both would
 * need a fallback fetch anyway, so the fallback becomes the only path and the shortcut is
 * dead code that produces one stale-data bug - showing a cached price when the real one
 * has changed.
 */
export function ProductDetailPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const toast = useToast()
  const { addItem, pendingItemId } = useCart()

  const [quantity, setQuantity] = useState(1)

  const product = useApiQuery((signal) => getProduct(id, { signal }), [id])

  /*
   * Related products - the same category, excluding this one.
   *
   * This is the backend's own specification filter doing the work: `categoryId` narrows it,
   * the page size caps it, and the exclusion happens here because the API has no
   * "not this id" parameter and adding one for a browse nicety would be the tail wagging
   * the dog. Fetching one extra and filtering locally is exact for this purpose.
   */
  const related = useApiQuery(
    (signal) =>
      listProducts(
        { categoryId: product.data?.category?.id, size: 5, sort: 'createdAt,desc' },
        { signal },
      ),
    [product.data?.category?.id],
    { skip: !product.data?.category?.id },
  )

  const item = product.data
  const available = item?.stock ?? 0
  const maxQuantity = Math.max(1, Math.min(available, 10))

  const handleAdd = async () => {
    const result = await addItem(item.id, quantity)
    if (result.ok) {
      toast.success(`${quantity} × ${item.name} added to your cart`, 'Added to cart')
    } else if (result.error?.code === 'INSUFFICIENT_STOCK') {
      toast.info(result.error.message, 'Not enough stock')
    } else {
      toast.fromError(result.error, 'Could not add to cart')
    }
  }

  /** Adds and goes straight to the cart - the common intent after "buy this one now". */
  const handleBuyNow = async () => {
    const result = await addItem(item.id, quantity)
    if (result.ok) navigate('/cart')
    else toast.fromError(result.error, 'Could not add to cart')
  }

  if (product.error) {
    const notFound = product.error.code === 'PRODUCT_NOT_FOUND'

    return (
      <div className="page">
        <div className="container">
          <ErrorState
            error={product.error}
            title={notFound ? 'Product not found' : 'Could not load this product'}
            onRetry={notFound ? undefined : () => product.refetch()}
          />
          <p className="u-center">
            <Link className="btn btn--secondary" to="/products">
              Back to shop
            </Link>
          </p>
        </div>
      </div>
    )
  }

  if (product.loading || !item) {
    return (
      <div className="page">
        <div className="container detail">
          <div className="detail__media">
            <Skeleton height="440px" radius="16px" />
          </div>
          <div className="detail__info">
            <Skeleton width="40%" height="0.9rem" />
            <Skeleton width="80%" height="2rem" />
            <Skeleton width="30%" height="1.6rem" />
            <Skeleton height="5rem" />
            <Skeleton width="50%" height="2.6rem" radius="10px" />
          </div>
        </div>
      </div>
    )
  }

  const outOfStock = available <= 0
  const relatedItems = (related.data?.content ?? []).filter((candidate) => candidate.id !== item.id).slice(0, 4)

  return (
    <div className="page">
      <div className="container">
        {/* Breadcrumbs give a way back to the filtered list, not just the home page. */}
        <nav className="breadcrumbs" aria-label="Breadcrumb">
          <Link to="/">Home</Link>
          <span aria-hidden="true">/</span>
          <Link to="/products">Shop</Link>
          {item.category && (
            <>
              <span aria-hidden="true">/</span>
              <Link to={`/products?categoryId=${item.category.id}`}>{item.category.name}</Link>
            </>
          )}
          <span aria-hidden="true">/</span>
          <span aria-current="page">{item.name}</span>
        </nav>

        <div className="detail">
          <div className="detail__media">
            {/*
             * The Suspense fallback is a skeleton with the same square aspect ratio as the
             * canvas, so the layout does not shift when the chunk arrives. A bare spinner
             * would reserve nothing and the page would jump.
             */}
            <Suspense fallback={<Skeleton height="100%" radius="16px" />}>
              <ProductViewer3D product={item} imageUrl={item.imageUrl} />
            </Suspense>
          </div>

          <div className="detail__info">
            {item.category && (
              <Link className="detail__category" to={`/products?categoryId=${item.category.id}`}>
                {item.category.name}
              </Link>
            )}

            <h1 className="detail__name">{item.name}</h1>

            <div className="detail__meta">
              <StockBadge stock={available} />
              {!item.active && <Badge tone="neutral">Unavailable</Badge>}
              {item.stock <= 5 && item.stock > 0 && <Badge tone="warning">Selling fast</Badge>}
            </div>

            <p className="detail__price">{formatMoney(item.price)}</p>

            {item.description && <p className="detail__description">{item.description}</p>}

            <div className="detail__buy">
              <div className="qty">
                <label className="qty__label" htmlFor="quantity">
                  Quantity
                </label>
                <div className="qty__control">
                  <button
                    type="button"
                    className="qty__button"
                    onClick={() => setQuantity((value) => Math.max(1, value - 1))}
                    disabled={quantity <= 1}
                    aria-label="Decrease quantity"
                  >
                    −
                  </button>
                  <input
                    id="quantity"
                    type="number"
                    className="qty__input"
                    min="1"
                    max={maxQuantity}
                    value={quantity}
                    onChange={(event) => {
                      const next = Number(event.target.value)
                      /*
                       * Clamped on input rather than only on blur. An unclamped box lets the
                       * user type 999 against 3 available, and the failure would arrive from
                       * the server as INSUFFICIENT_STOCK - technically correct, but a worse
                       * experience than a control that simply cannot express the request.
                       */
                      if (Number.isNaN(next)) return setQuantity(1)
                      setQuantity(Math.min(Math.max(1, next), maxQuantity))
                    }}
                    disabled={outOfStock}
                  />
                  <button
                    type="button"
                    className="qty__button"
                    onClick={() => setQuantity((value) => Math.min(maxQuantity, value + 1))}
                    disabled={quantity >= maxQuantity}
                    aria-label="Increase quantity"
                  >
                    +
                  </button>
                </div>
                {available > 0 && available <= 5 && (
                  <p className="qty__hint">Only {available} left in stock.</p>
                )}
              </div>

              <div className="detail__actions">
                <button
                  type="button"
                  className="btn btn--primary"
                  onClick={handleAdd}
                  disabled={outOfStock || pendingItemId === item.id}
                >
                  {pendingItemId === item.id ? 'Adding…' : 'Add to cart'}
                </button>
                <button
                  type="button"
                  className="btn btn--secondary"
                  onClick={handleBuyNow}
                  disabled={outOfStock || pendingItemId === item.id}
                >
                  Buy now
                </button>
              </div>
            </div>

            {/*
             * The stock caveat is stated rather than hidden. The backend re-checks stock
             * inside the checkout transaction, so this is accurate while it is on screen and
             * may not be by the time the order is placed - which is a property of the system,
             * not a bug, and worth one honest sentence.
             */}
            <p className="detail__note">
              Stock is confirmed when the order is placed. Prices are locked in at that moment.
            </p>

            <dl className="detail__facts">
              <div>
                <dt>Product ID</dt>
                <dd>#{item.id}</dd>
              </div>
              <div>
                <dt>Availability</dt>
                <dd>{outOfStock ? 'Out of stock' : `${available} in stock`}</dd>
              </div>
            </dl>
          </div>
        </div>

        {relatedItems.length > 0 && (
          <section className="related">
            <h2 className="section-title">More in {item.category?.name}</h2>
            <div className="product-grid">
              {relatedItems.map((candidate) => (
                <ProductCard key={candidate.id} product={candidate} />
              ))}
            </div>
          </section>
        )}
      </div>
    </div>
  )
}
