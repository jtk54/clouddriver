package com.netflix.spinnaker.clouddriver.search.executor

import com.netflix.spinnaker.clouddriver.search.SearchProvider
import com.netflix.spinnaker.clouddriver.search.SearchQueryCommand
import com.netflix.spinnaker.clouddriver.search.SearchResultSet
import com.netflix.spinnaker.hystrix.SimpleHystrixCommand
import groovy.util.logging.Slf4j
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component

@Slf4j
@Component
@ConditionalOnExpression('${search.hystrix.enabled:false}')
class SearchWhy {
  private static final String GROUP_KEY = "SearchProviderCommand"

  SearchWhy() {}

  List<SearchResultSet> searchAllProviders(List<SearchProvider> providers,
                                           SearchQueryCommand searchQuery) {
    println ",, sim sim ala bim"
    def commands = providers.collect { p -> return searchProviderCommand(p, searchQuery) }
    return commands.collect { c -> c.execute() } // No idea how the hystrix config affects this.
  }

  static SimpleHystrixCommand<SearchResultSet> searchProviderCommand(SearchProvider provider, SearchQueryCommand searchQuery) {
    return new SimpleHystrixCommand<SearchResultSet>(GROUP_KEY,
      provider.getClass().getSimpleName(),
      { searchSingleProvider(provider, searchQuery) })
  }

  static SearchResultSet searchSingleProvider(SearchProvider provider, SearchQueryCommand searchQuery) {
    Map<String, String> filters = searchQuery.filters.findAll {
      !provider.excludedFilters().contains(it.key)
    }

    try {
      if (searchQuery.type && !searchQuery.type.isEmpty()) {
        provider.search(searchQuery.q, searchQuery.type, searchQuery.page, searchQuery.pageSize, filters)
      } else {
        provider.search(searchQuery.q, searchQuery.page, searchQuery.pageSize, filters)
      }
    } catch (Exception e) {
      log.error("Search for '${searchQuery.q}' in '${provider.platform}' failed", e)
      new SearchResultSet(totalMatches: 0, results: [])
    }
  }
}
