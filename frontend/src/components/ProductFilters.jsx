import { PRODUCT_SORT_OPTIONS } from '../api/products'

/**
 * The product filter bar.
 *
 * <h2>Every control here maps to a real backend parameter</h2>
 *
 * The backend's `ProductFilter` supports keyword, categoryId, minPrice, maxPrice, active and
 * sort, all applied in SQL through a JPA specification. Nothing on this bar is filtered in
 * the browser, which is what makes the result count and the pagination correct - filtering
 * a 12-item page client-side would show "3 results" when there are 400.
 *
 * The filters are also deliberately NOT applied as you type. Each change updates local form
 * state, and the parent commits it. Live filtering on this many parameters means a request
 * per keystroke across five fields, and the backend's specification query cannot be served
 * from cache because every combination is a distinct query.
 */
export function ProductFilters({ value, onChange, onReset, categories = [], disabled }) {
  const update = (patch) => onChange({ ...value, ...patch })

  const hasFilters =
    Boolean(value.keyword) ||
    Boolean(value.categoryId) ||
    Boolean(value.minPrice) ||
    Boolean(value.maxPrice) ||
    Boolean(value.sort)

  return (
    <form
      className="filters"
      /*
       * Intercepting submit is required even though there is no submit button: pressing
       * Enter in a text input submits the form, and the browser's default is to reload the
       * page - which would drop all query state and re-mount the SPA.
       */
      onSubmit={(event) => {
        event.preventDefault()
        onChange({ ...value })
      }}
      role="search"
    >
      <div className="filters__row">
        <div className="filters__search">
          <label className="sr-only" htmlFor="filter-keyword">
            Search products
          </label>
          <input
            id="filter-keyword"
            type="search"
            className="filters__input"
            placeholder="Search products…"
            value={value.keyword ?? ''}
            onChange={(event) => update({ keyword: event.target.value })}
            disabled={disabled}
            /*
             * maxLength matches the backend's @Size(max = 100). Enforcing it here means the
             * user gets a constraint they cannot exceed, rather than a 400 after the fact.
             */
            maxLength={100}
          />
        </div>

        <div className="filters__group">
          <label className="filters__label" htmlFor="filter-category">
            Category
          </label>
          <select
            id="filter-category"
            className="filters__select"
            value={value.categoryId ?? ''}
            onChange={(event) => update({ categoryId: event.target.value || undefined })}
            disabled={disabled}
          >
            <option value="">All categories</option>
            {categories.map((category) => (
              <option key={category.id} value={category.id}>
                {category.name}
              </option>
            ))}
          </select>
        </div>

        <div className="filters__group">
          <label className="filters__label" htmlFor="filter-min">
            Min price
          </label>
          <input
            id="filter-min"
            type="number"
            className="filters__input filters__input--number"
            /* Matches the backend's DECIMAL(19,2): two decimal places, no negatives. */
            min="0"
            step="0.01"
            placeholder="0"
            value={value.minPrice ?? ''}
            onChange={(event) => update({ minPrice: event.target.value || undefined })}
            disabled={disabled}
          />
        </div>

        <div className="filters__group">
          <label className="filters__label" htmlFor="filter-max">
            Max price
          </label>
          <input
            id="filter-max"
            type="number"
            className="filters__input filters__input--number"
            min="0"
            step="0.01"
            placeholder="Any"
            value={value.maxPrice ?? ''}
            onChange={(event) => update({ maxPrice: event.target.value || undefined })}
            disabled={disabled}
          />
        </div>

        <div className="filters__group">
          <label className="filters__label" htmlFor="filter-sort">
            Sort by
          </label>
          <select
            id="filter-sort"
            className="filters__select"
            value={value.sort ?? ''}
            onChange={(event) => update({ sort: event.target.value || undefined })}
            disabled={disabled}
          >
            {/* Sourced from the API module so the list and the validator cannot drift. */}
            {PRODUCT_SORT_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </div>

        {hasFilters && (
          <button type="button" className="btn btn--ghost btn--sm filters__reset" onClick={onReset}>
            Clear filters
          </button>
        )}
      </div>
    </form>
  )
}
