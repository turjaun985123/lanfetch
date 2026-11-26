//! Poster fetch state machine.
//!
//! Extracted from main.rs (50-wave fix program, Wave 1) so the
//! enqueue → dispatch → resolve cycle is unit-testable without the
//! Rune runtime. History: the dispatch loop in poll() used to pop a
//! queued URL and skip it whenever the render-state map contained
//! that URL — but enqueue had already inserted a Loading marker into
//! that same map, so every URL was skipped and no poster fetch was
//! ever dispatched (v1.2.0–v1.4.0: every card fell back to the
//! initials tile). This queue is now the single owner of the fetch
//! lifecycle: a URL is dispatchable exactly while it is Queued, and
//! it is dispatched exactly once.

use std::collections::{HashMap, VecDeque};

/// Lifecycle of one poster URL inside the fetch queue.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum FetchState {
    Queued,
    InFlight,
    Done,
    Failed,
}

/// FIFO fetch queue with per-URL lifecycle and diagnostics counters.
pub struct PosterQueue {
    states: HashMap<String, FetchState>,
    queue: VecDeque<String>,
    pub dispatched: usize,
    pub succeeded: usize,
    pub failed: usize,
}

impl PosterQueue {
    pub fn new() -> Self {
        Self {
            states: HashMap::new(),
            queue: VecDeque::new(),
            dispatched: 0,
            succeeded: 0,
            failed: 0,
        }
    }

    /// Register a URL for fetching. Returns true only when the URL
    /// was newly queued; a URL known in ANY state (queued, in
    /// flight, done, failed) is never queued twice.
    pub fn enqueue(&mut self, url: &str) -> bool {
        if self.states.contains_key(url) {
            return false;
        }
        self.states.insert(url.to_string(), FetchState::Queued);
        self.queue.push_back(url.to_string());
        true
    }

    /// Pop the next dispatchable URL and mark it in flight. This is
    /// the decision the old poll() loop got wrong: it must hand out
    /// every enqueued URL exactly once.
    pub fn next_dispatch(&mut self) -> Option<String> {
        while let Some(url) = self.queue.pop_front() {
            if self.states.get(&url) == Some(&FetchState::Queued) {
                self.states.insert(url.clone(), FetchState::InFlight);
                self.dispatched += 1;
                return Some(url);
            }
        }
        None
    }

    /// Record the outcome of an in-flight fetch. Resolving a URL
    /// that is not in flight is a no-op, so a late or duplicate
    /// completion can never corrupt the counters.
    pub fn resolve(&mut self, url: &str, ok: bool) {
        if self.states.get(url) == Some(&FetchState::InFlight) {
            if ok {
                self.states.insert(url.to_string(), FetchState::Done);
                self.succeeded += 1;
            } else {
                self.states.insert(url.to_string(), FetchState::Failed);
                self.failed += 1;
            }
        }
    }

    /// How many URLs are still waiting to be dispatched.
    pub fn pending(&self) -> usize {
        self.queue.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn enqueue_then_dispatch_issues_every_url() {
        // Regression test for the v1.2.0–v1.4.0 bug: enqueue marked
        // each URL as Loading in the same map the dispatcher checked,
        // so every queued URL was discarded and zero fetches ran.
        let mut q = PosterQueue::new();
        assert!(q.enqueue("http://h/a.jpg"));
        assert!(q.enqueue("http://h/b.jpg"));
        assert!(q.enqueue("http://h/c.jpg"));
        assert_eq!(q.next_dispatch().as_deref(), Some("http://h/a.jpg"));
        assert_eq!(q.next_dispatch().as_deref(), Some("http://h/b.jpg"));
        assert_eq!(q.next_dispatch().as_deref(), Some("http://h/c.jpg"));
        assert_eq!(q.next_dispatch(), None);
        assert_eq!(q.dispatched, 3);
    }

    #[test]
    fn enqueue_dedups_in_every_state() {
        let mut q = PosterQueue::new();
        assert!(q.enqueue("http://h/a.jpg"));
        assert!(!q.enqueue("http://h/a.jpg")); // already queued
        let url = q.next_dispatch().unwrap();
        assert!(!q.enqueue(&url)); // in flight
        q.resolve(&url, true);
        assert!(!q.enqueue(&url)); // done: never refetched
    }

    #[test]
    fn failed_fetch_is_not_redispatched() {
        let mut q = PosterQueue::new();
        assert!(q.enqueue("http://h/x.jpg"));
        let url = q.next_dispatch().unwrap();
        q.resolve(&url, false);
        assert_eq!(q.failed, 1);
        assert!(!q.enqueue("http://h/x.jpg"));
        assert_eq!(q.next_dispatch(), None);
    }

    #[test]
    fn resolve_counts_only_in_flight() {
        let mut q = PosterQueue::new();
        assert!(q.enqueue("http://h/a.jpg"));
        q.resolve("http://h/a.jpg", true); // still queued: ignored
        assert_eq!(q.succeeded, 0);
        let url = q.next_dispatch().unwrap();
        q.resolve(&url, true);
        q.resolve(&url, true); // duplicate resolve: ignored
        assert_eq!(q.succeeded, 1);
    }

    #[test]
    fn dispatch_is_fifo() {
        let mut q = PosterQueue::new();
        for n in ["1", "2", "3", "4", "5"] {
            assert!(q.enqueue(n));
        }
        let mut order = Vec::new();
        while let Some(u) = q.next_dispatch() {
            order.push(u);
        }
        assert_eq!(order.join(","), "1,2,3,4,5");
        assert_eq!(q.pending(), 0);
    }
}
