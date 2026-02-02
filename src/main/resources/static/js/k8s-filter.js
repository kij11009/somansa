/**
 * K8s Doctor - Reusable Resource Filter Component
 * Provides client-side filtering for Kubernetes resource lists
 */
class K8sFilter {
  /**
   * Initialize the filter component
   * @param {string} tableId - ID of the table to filter
   * @param {Object} options - Configuration options
   * @param {HTMLElement} options.searchInput - Search input element
   * @param {HTMLElement} options.namespaceFilter - Namespace dropdown element
   * @param {HTMLElement} options.statusFilter - Status dropdown element
   * @param {HTMLElement} options.resultCount - Element to display result count
   */
  constructor(tableId, options = {}) {
    this.table = document.getElementById(tableId);
    if (!this.table) {
      console.error(`Table with ID "${tableId}" not found`);
      return;
    }

    this.tbody = this.table.querySelector('tbody');
    this.rows = Array.from(this.tbody.querySelectorAll('tr'));
    this.totalCount = this.rows.length;

    // Filter elements
    this.searchInput = options.searchInput;
    this.namespaceFilter = options.namespaceFilter;
    this.statusFilter = options.statusFilter;
    this.resultCount = options.resultCount;

    // Bind event listeners
    this.bindEvents();

    // Initial count display
    this.updateResultCount(this.totalCount);
  }

  /**
   * Bind event listeners to filter controls
   */
  bindEvents() {
    if (this.searchInput) {
      this.searchInput.addEventListener('input', () => this.applyFilters());
    }

    if (this.namespaceFilter) {
      this.namespaceFilter.addEventListener('change', () => this.applyFilters());
    }

    if (this.statusFilter) {
      this.statusFilter.addEventListener('change', () => this.applyFilters());
    }
  }

  /**
   * Apply all active filters to the table
   */
  applyFilters() {
    const searchTerm = this.searchInput?.value.toLowerCase().trim() || '';
    const selectedNamespace = this.namespaceFilter?.value || '';
    const selectedStatus = this.statusFilter?.value || '';

    let visibleCount = 0;

    this.rows.forEach(row => {
      const shouldShow = this.matchesFilters(row, searchTerm, selectedNamespace, selectedStatus);

      if (shouldShow) {
        row.style.display = '';
        visibleCount++;
      } else {
        row.style.display = 'none';
      }
    });

    this.updateResultCount(visibleCount);
  }

  /**
   * Check if a row matches all active filters
   * @param {HTMLElement} row - Table row element
   * @param {string} searchTerm - Search query
   * @param {string} namespace - Selected namespace
   * @param {string} status - Selected status
   * @returns {boolean} True if row matches all filters
   */
  matchesFilters(row, searchTerm, namespace, status) {
    // Search filter (by resource name)
    if (searchTerm) {
      const rowText = row.textContent.toLowerCase();
      if (!rowText.includes(searchTerm)) {
        return false;
      }
    }

    // Namespace filter
    if (namespace) {
      const rowNamespace = row.dataset.namespace || '';
      if (rowNamespace !== namespace) {
        return false;
      }
    }

    // Status filter
    if (status) {
      const rowStatus = (row.dataset.status || '').toLowerCase();
      if (rowStatus !== status.toLowerCase()) {
        return false;
      }
    }

    return true;
  }

  /**
   * Update the result count display
   * @param {number} count - Number of visible rows
   */
  updateResultCount(count) {
    if (this.resultCount) {
      if (count === this.totalCount) {
        this.resultCount.textContent = `Showing ${count} resource${count !== 1 ? 's' : ''}`;
      } else {
        this.resultCount.textContent = `Showing ${count} of ${this.totalCount} resource${this.totalCount !== 1 ? 's' : ''}`;
      }
    }
  }

  /**
   * Reset all filters
   */
  reset() {
    if (this.searchInput) this.searchInput.value = '';
    if (this.namespaceFilter) this.namespaceFilter.value = '';
    if (this.statusFilter) this.statusFilter.value = '';
    this.applyFilters();
  }
}

// Export for use in templates
window.K8sFilter = K8sFilter;
