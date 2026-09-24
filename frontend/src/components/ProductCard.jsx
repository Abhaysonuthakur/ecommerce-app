import { useState } from 'react'
import { Link } from 'react-router-dom'
import { formatMoney, truncate } from '../lib/format'
import { Badge, StockBadge } from './ui'
import { useCart } from '../context/CartContext'
import { useToast } from '../context/ToastContext'

/**
 * A product card for grids and search results.
 *
 * <h2>Why adding to the cart is handled here and not by the parent</h2>
 *
 * The card owns its own "adding…" state, so a slow request disables one button rather than
 * freezing the whole grid. The parent would need to track which card was pending, which is
 * the same information kept in a worse place.
 *
 * The result of the call is reported through a toast rather than inline, because the card
 * may be unmounted by the time it completes - if a filter change re-renders the grid, an
 * inline error would vanish with it and the user would see nothing at all.
 */
export function ProductCard({ product, onAdded }) {
  const { addItem } = useCart()
  const toast = useToast()
  const [adding, setAdding] = useState(false)

  const outOfStock = (product.stock ?? 0) <= 0

  const handleAdd = async (event) => {
    // The card is wrapped in a Link; without this the click would also navigate to the
    // product page, so adding to the cart from the grid would throw the user off the grid.
    event.preventDefault()
    event.stopPropagation()

    setAdding(true)
    const result = await addItem(product.id, 1)
    setAdding(false)

    if (result.ok) {
      toast.success(`${product.name} added to your cart`, 'Added')
      onAdded?.(product)
    } else {
      /*
       * INSUFFICIENT_STOCK is the expected outcome when someone else bought the last unit
       * between this page rendering and the click. It is shown as a plain message rather
       * than an error tone, because nothing is broken - the inventory is simply gone.
       */
      if (result.error?.code === 'INSUFFICIENT_STOCK') {
        toast.info(result.error.message, 'Not enough stock')
      } else {
        toast.fromError(result.error, 'Could not add to cart')
      }
    }
  }

  return (
    <Link to={`/products/${product.id}`} className="product-card">
      <div className="product-card__media">
        {product.imageUrl ? (
          /*
           * loading="lazy" defers off-screen images. alt carries the product name rather
           * than being empty: the image is the product, not decoration, so a screen reader
           * should announce it - and if the image fails, the text is the fallback.
           */
          <img src={product.imageUrl} alt={product.name} loading="lazy" />
        ) : (
          // A deliberate placeholder, not a broken image icon. Most seed data has no image,
          // and an empty box reads as "no image available" where a missing-image glyph
          // reads as "this page is broken".
          <div className="product-card__placeholder" aria-hidden="true">
            <span>{product.name?.charAt(0) ?? '?'}</span>
          </div>
        )}

        <div className="product-card__badges">
          <StockBadge stock={product.stock} />
          {/* Only when it means something - a badge on every card is not a signal. */}
          {product.stock > 0 && product.stock <= 5 && <Badge tone="warning">Low stock</Badge>}
        </div>
      </div>

      <div className="product-card__body">
        {product.category && (
          <p className="product-card__category">{product.category.name}</p>
        )}

        <h3 className="product-card__name">{product.name}</h3>

        {product.description && (
          <p className="product-card__description">{truncate(product.description, 80)}</p>
        )}

        <div className="product-card__footer">
          <span className="product-card__price">{formatMoney(product.price)}</span>

          <button
            type="button"
            className="btn btn--primary btn--sm"
            onClick={handleAdd}
            disabled={adding || outOfStock}
            aria-label={outOfStock ? `${product.name} is out of stock` : `Add ${product.name} to cart`}
          >
            {adding ? 'Adding…' : outOfStock ? 'Out of stock' : 'Add to cart'}
          </button>
        </div>
      </div>
    </Link>
  )
}

/** A responsive grid of cards. */
export function ProductGrid({ products, onAdded }) {
  return (
    <div className="product-grid">
      {products.map((product) => (
        <ProductCard key={product.id} product={product} onAdded={onAdded} />
      ))}
    </div>
  )
}

/**
 * Skeleton cards in the real grid.
 *
 * Rendered inside ProductGrid rather than as a separate block so the skeleton occupies
 * exactly the same cells the results will - which is the entire point of a skeleton.
 */
export function ProductGridSkeleton({ count = 8 }) {
  return (
    <div className="product-grid">
      {Array.from({ length: count }, (_, index) => (
        <div key={index} className="skeleton-card">
          <div className="skeleton skeleton--media" />
          <div className="skeleton skeleton--line" style={{ width: '70%' }} />
          <div className="skeleton skeleton--line" style={{ width: '45%' }} />
          <div className="skeleton skeleton--line" style={{ width: '35%', height: '1.4rem' }} />
        </div>
      ))}
    </div>
  )
}
