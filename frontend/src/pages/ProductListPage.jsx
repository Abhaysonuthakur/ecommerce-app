import { useCallback, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { listCategories, listProducts } from '../api/products'
import { ProductFilters } from '../components/ProductFilters'
import { ProductGrid, ProductGridSkeleton } from '../components/ProductCard'
import { EmptyState, ErrorState, Pagination } from '../components/ui'
import { useApiQuery, useDebounced } from '../hooks/useApi'
import { config } from '../config'

/*
 * The catalogue's page size, from `VITE_PRODUCTS_PAGE_SIZE`.
 *
 * Kept as a local alias rather than reading `config.productsPageSize` at each call site, so the
 * three uses below cannot drift apart. `config.js` clamps it to the backend's own `@Min(1)`
 * / `@Max(100)` bounds, so an out-of-range value here is a typo, not a 400.
 *
 * This was previously the literal `12`, which is exactly the bug the config module exists to
 * prevent: the variable was documented in `.env.example`, read into `config`, and then ignored
 * by the one page a reader would test it on.
 */
const PAGE_SIZE = config.productsPageSize

/**
 * The product catalogue.
 *
 * <h2>Filter state lives in the URL, not in component state</h2>
 *
 * This is the single most consequential decision on this page. Keeping the filters in
 * `useState` is simpler and fails in four ways that users notice immediately:
 *
 *   1. The back button does not work. Pick a category, change pages, press back - and you
 *      are back on the home page, because there is no history entry for the intermediate
 *      filter states.
 *   2. A filtered view cannot be shared or bookmarked. The URL is the only way to express
 *      "this exact list".
 *   3. A refresh silently discards what the user did.
 *   4. Deep links from elsewhere cannot preselect a category.
 *
 * With the URL as the source of truth all four work, and the cost is one hook. The URL is
 * read on every render and every filter change writes to it - the query is derived from it,
 * so there is no second copy to keep in sync.
 */
export function ProductListPage() {
  const [searchParams, setSearchParams] = useSearchParams()

  /*
   * The search box needs to feel instant while the query is debounced, so the raw input is
   * held separately and only its debounced form reaches the URL. Writing to the URL on every
   * keystroke would flood history with an entry per character, making the back button
   * useless in a different way.
   */
  const [keywordInput, setKeywordInput] = useState(() => searchParams.get('keyword') ?? '')
  const debouncedKeyword = useDebounced(keywordInput, 400)

  /** Reads one parameter, returning undefined rather than null so the client omits it. */
  const param = useCallback((name) => searchParams.get(name) || undefined, [searchParams])

  /**
   * The filter object handed to the API.
   *
   * Rebuilt only when the URL changes, so the query hook's dependency array is stable. The
   * debounced search term is merged in here rather than being written straight to the URL -
   * that is what stops every keystroke from becoming a history entry while still letting the
   * request see the current text.
   */
  const filters = useMemo(
    () => ({
      page: Number(searchParams.get('page') ?? 0),
      size: PAGE_SIZE,
      keyword: debouncedKeyword || undefined,
      categoryId: param('categoryId'),
      minPrice: param('minPrice'),
      maxPrice: param('maxPrice'),
      sort: param('sort'),
    }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [searchParams, debouncedKeyword],
  )

  const categories = useApiQuery((signal) => listCategories({ signal }), [])

  const products = useApiQuery(
    (signal) => listProducts(filters, { signal }),
    [filters.page, filters.keyword, filters.categoryId, filters.minPrice, filters.maxPrice, filters.sort],
  )

  /**
   * Commits a filter change to the URL, resetting to page 0.
   *
   * The reset is essential and easy to forget. Staying on page 3 while narrowing a filter
   * usually produces an empty result set for a list that has plenty of matches on page 1 -
   * which reads as "no results" rather than "you are past the end". The backend handles an
   * out-of-range page correctly by returning empty content, so nothing manages to break; it
   * just looks wrong.
   */
  const applyFilters = useCallback(
    (next) => {
      const params = new URLSearchParams()
      const merged = { ...filters, ...next }

      if (merged.keyword) params.set('keyword', merged.keyword)
      if (merged.categoryId) params.set('categoryId', merged.categoryId)
      if (merged.minPrice) params.set('minPrice', merged.minPrice)
      if (merged.maxPrice) params.set('maxPrice', merged.maxPrice)
      if (merged.sort) params.set('sort', merged.sort)
      // page is deliberately omitted at 0, keeping the canonical URL clean.

      setSearchParams(params)
    },
    [filters, setSearchParams],
  )

  const resetFilters = useCallback(() => {
    setKeywordInput('')
    setSearchParams(new URLSearchParams())
  }, [setSearchParams])

  const goToPage = useCallback(
    (page) => {
      const params = new URLSearchParams(searchParams)
      if (page <= 0) params.delete('page')
      else params.set('page', String(page))
      setSearchParams(params)
      // Without this the user lands mid-page on the new results and has to scroll up.
      window.scrollTo({ top: 0, behavior: 'smooth' })
    },
    [searchParams, setSearchParams],
  )

  const page = products.data
  const activeFilterCount = ['categoryId', 'minPrice', 'maxPrice'].filter((name) => param(name)).length

  return (
    <div className="page">
      <div className="container">
        <header className="page__head">
          <div>
            <h1 className="page__title">Shop</h1>
            <p className="page__subtitle">
              {page
                ? `${page.totalElements} product${page.totalElements === 1 ? '' : 's'}${
                    filters.keyword ? ` matching “${filters.keyword}”` : ''
                  }`
                : 'Browsing the catalogue'}
            </p>
          </div>
        </header>

        <ProductFilters
          /*
           * The form shows the raw keyword, not the debounced one - otherwise the text the
           * user just typed would briefly disappear from the box as they type.
           */
          value={{ ...filters, keyword: keywordInput }}
          onChange={(next) => {
            if (next.keyword !== keywordInput) {
              setKeywordInput(next.keyword ?? '')
              return
            }
            applyFilters(next)
          }}
          onReset={resetFilters}
          categories={categories.data ?? []}
          disabled={products.loading && !page}
        />

        {products.error && !page ? (
          <ErrorState
            error={products.error}
            title="Could not load products"
            onRetry={() => products.refetch()}
          />
        ) : products.loading && !page ? (
          <ProductGridSkeleton count={PAGE_SIZE} />
        ) : page?.content?.length ? (
          <>
            {/*
             * Dim during a refetch rather than unmounting the grid. Replacing results with a
             * skeleton on every filter change makes the page flash and loses scroll position.
             */}
            <div className={products.loading ? 'is-refetching' : undefined}>
              <ProductGrid products={page.content} />
            </div>

            <Pagination
              page={page.page}
              totalPages={page.totalPages}
              first={page.first}
              last={page.last}
              totalElements={page.totalElements}
              onChange={goToPage}
              label="products"
            />
          </>
        ) : (
          <EmptyState
            title="No products found"
            message={
              activeFilterCount || filters.keyword
                ? 'Try widening your search or clearing the filters.'
                : 'This store has no products yet.'
            }
            action={
              (activeFilterCount || filters.keyword) && (
                <button type="button" className="btn btn--secondary" onClick={resetFilters}>
                  Clear filters
                </button>
              )
            }
          />
        )}
      </div>
    </div>
  )
}
