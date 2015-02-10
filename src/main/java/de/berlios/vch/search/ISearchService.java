package de.berlios.vch.search;

import de.berlios.vch.parser.IOverviewPage;
import de.berlios.vch.parser.IWebPage;

public interface ISearchService {
	public IOverviewPage search(String query);
	
	public IWebPage parse(IWebPage page) throws Exception;
}
