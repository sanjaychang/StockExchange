/*
 * File     : IOIset.java
 *
 * Author   : Zoltan Feledy
 * 
 * Contents : This class is a Set of IOI objects with a utility
 *            methods to access the individual iois.
 * 
 */

package quickfix.examples.executor;

import java.util.ArrayList;
import java.util.Iterator;

public class IOIset {
    private ArrayList<IOI> iois = new ArrayList<IOI>();

    public IOIset() {}
    
	public void add(IOI ioi) {
		iois.add(ioi);
		int limit = 50;
		while (iois.size() > limit) {
			iois.remove(0);
		}
	}
    
        
    public int getCount() {
        return iois.size();
    }

    public IOI getIOI( int i ) {
        return iois.get( i );
    }

    public IOI getIOI( String id ) {
        Iterator<IOI> iterator = iois.iterator();
        while ( iterator.hasNext() ){
            IOI ioi = iterator.next();
            if ( ioi.getID().equals(id) )
                return ioi;
        }
        return null;
    }
}
