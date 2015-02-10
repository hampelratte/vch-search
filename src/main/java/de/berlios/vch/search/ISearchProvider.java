package de.berlios.vch.search;

import de.berlios.vch.parser.IOverviewPage;
import de.berlios.vch.parser.IWebPage;

public interface ISearchProvider {
	public String getId();
	
	public String getName();
	
    public IOverviewPage search(String query) throws Exception;
    
    public IWebPage parse(IWebPage page) throws Exception;
}
