import { Component } from 'react'
import { Link } from 'react-router-dom'

/**
 * Catches render-time errors so one broken page does not blank the whole app.
 *
 * <h2>Why this must be a class component</h2>
 *
 * `getDerivedStateFromError` and `componentDidCatch` are the only mechanisms React provides
 * for this, and they have no hook equivalent. This is the one legitimate reason to write a
 * class in a modern React codebase - it is not a style preference.
 *
 * <h2>What it does and does not catch</h2>
 *
 * It catches errors thrown while rendering, in lifecycle methods, and in constructors below
 * it. It does NOT catch errors in event handlers, in asynchronous code, or during server
 * rendering. That is not a limitation to work around: an event handler is not rendering, so
 * there is no partial tree to replace - those failures belong in the try/catch that the API
 * client already provides, surfaced through a toast.
 */
export class ErrorBoundary extends Component {
  constructor(props) {
    super(props)
    this.state = { error: null }
  }

  static getDerivedStateFromError(error) {
    return { error }
  }

  componentDidCatch(error, info) {
    /*
     * Logged rather than swallowed. In development this is where the component stack - which
     * is far more useful than the JavaScript stack for a render error - appears.
     */
    console.error('Unhandled render error:', error, info.componentStack)
  }

  render() {
    if (!this.state.error) return this.props.children

    return (
      <div className="crash">
        <h1 className="crash__title">Something went wrong</h1>
        <p className="crash__message">
          The page failed to render. This is a bug in the frontend, not a problem with your
          account or your data.
        </p>

        {/*
         * The message is shown because this is a learning project and the person reading it
         * is usually the one who can fix it. In a production consumer app, leaking an
         * internal error message to a customer would be a poor choice.
         */}
        <pre className="crash__detail">{String(this.state.error?.message ?? this.state.error)}</pre>

        <div className="crash__actions">
          <button type="button" className="btn btn--primary" onClick={() => window.location.reload()}>
            Reload the page
          </button>
          <Link className="btn btn--secondary" to="/" onClick={() => this.setState({ error: null })}>
            Go home
          </Link>
        </div>
      </div>
    )
  }
}
