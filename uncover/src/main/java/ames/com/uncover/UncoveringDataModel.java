/*
Copyright 2017 Audrius Meskauskas

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */
package ames.com.uncover;

import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import ames.com.uncover.impl.AsyncBridge;
import ames.com.uncover.impl.AvailableSegment;
import ames.com.uncover.impl.DataFetchManager;
import ames.com.uncover.primary.PrimaryDataProvider;
import ames.com.uncover.primary.Query;
import ames.com.uncover.primary.SearchCompleteListener;

/**
 * Central class of implementation, providing the fast model for viewing from one side and grouped/async interface from
 * another side.
 * <p>
 * The model is itself the DataAvailableListener, but would also propagate the data available event to
 * the view for repainting,
 */
public class UncoveringDataModel<ITEM> {
  private static final String TAG = "Model";


  /**
   * The number of items in the page
   */
  private int pageSize = 10;

  /**
   * The total length of the list presented
   */
  private int size = 0;

  /**
   * The listener of the view or presenter that the model notifies when the data have changed
   * due update arrivals.
   */
  private DataChangeListener dataAvailableListener;

  /**
   * The delegate where the model requests fetching the new data when needed.
   */
  private DataFetchManager dataFetcher;

  /**
   * Already available data items, maps page number to the data array.
   */
  private Map<Integer, AvailableSegment<ITEM>> data = new ConcurrentHashMap<>();

  /**
   * The "empty" item indicating data have been requested and now are in the process of loading.
   */
  private ITEM loadingPlaceHolder;

  private Query query;

  private SearchCompleteListener searchCompleteListener;

  /**
   * Tracks if the current "data available" call is the first result for this query.
   * This allow to have the "search complete" listener for tasks like hiding the progress bar.
   */
  private boolean firstQueryResult = true;

  public void setPrimaryDataProvider(PrimaryDataProvider provider) {
    dataFetcher = new DataFetchManager();
    final AsyncBridge<ITEM> bridge = new AsyncBridge<>(provider);
    this.dataFetcher.setDataFetcher(bridge);
    this.dataFetcher.setDataModel(this);
    if (query != null) {
      requestPage(0);
    }
  }

  public void setQuery(Query query) {
    reset();
    this.query = query;

    // Automatically request the first block. Once fetched, the listeners will fire.
    if (dataFetcher != null) {
      requestPage(0);
    }
  }

  public int getPageSize() {
    return pageSize;
  }

  public UncoveringDataModel setPageSize(int pageSize) {
    this.pageSize = pageSize;
    return this;
  }

  public int getPage(int position) {
    return (position / pageSize);
  }

  /**
   * Set listener that receives notifications when data are loaded.
   */
  public UncoveringDataModel setDataAvailableListener(DataChangeListener dataAvailableListener) {
    this.dataAvailableListener = dataAvailableListener;
    return this;
  }

  /**
   * Get the item returned to indicate data are still being loaded. If not set, null is used.
   */
  public ITEM getLoadingPlaceHolder() {
    return loadingPlaceHolder;
  }

  /**
   * Set the item returned to indicate data are still being loaded. If not set, null is used.
   */
  public UncoveringDataModel setLoadingPlaceHolder(ITEM loadingPlaceHolder) {
    this.loadingPlaceHolder = loadingPlaceHolder;
    return this;
  }

  /**
   * Get item at the given position. If the value is not known, returns loading place holder and starts
   * fetching the data segment in the background.
   *
   * @param position position of the item to get
   * @return item at the given position.
   */
  public ITEM getItem(int position) {
    int page = getPage(position);
    AvailableSegment<ITEM> segment = data.get(page);
    if (segment != null) {
      return segment.get(position);
    }

    requestPage(page);
    return loadingPlaceHolder;
  }

  /**
   * Request the content for the given position. Expanding strategies must be applied and
   * then the data fetcher must be invoked.
   *
   * @param page the page currently missing data.
   */
  protected void requestPage(int page) {
    if (dataFetcher != null && !dataFetcher.alreadyFetching(page)) {
      dataFetcher.requestData(page);
    }
  }

  public DataFetchManager getDataFetcher() {
    return dataFetcher;
  }

  public UncoveringDataModel setDataFetcher(DataFetchManager dataFetcher) {
    this.dataFetcher = dataFetcher;
    return this;
  }

  /**
   * Get the number of items in the model. If not explicitly set, applies the best guess.
   *
   * @return number of items in the model.
   */
  public int size() {
    return size;
  }

  public UncoveringDataModel setSize(int size) {
    this.size = size;
    return this;
  }

  /**
   * Invoked by data fetcher when data become available.
   *
   * @param segment - the segment of now newly available data.
   */
  public void dataAvailable(AvailableSegment<ITEM> segment) {
    data.put(segment.getPage(), segment);

    size = Math.max(size, segment.getMaxIndex());
    if (dataAvailableListener != null) {
      dataAvailableListener.dataChanged(segment.getFrom(), segment.getTo(), size);
    }
    if (firstQueryResult && searchCompleteListener != null) {
      searchCompleteListener.onQuerySearchComplete(query);
    }
    firstQueryResult = false;
  }

  /**
   * Reset the model, making the data set empty.
   */
  public void reset() {
    firstQueryResult = true;
    size = 0;
    data.clear();
    if (dataFetcher != null) {
      dataFetcher.reset();
    }
  }

  /**
   * This can be called on low memory conditions to drop the cached data.
   */
  public void lowMemory() {
    data.clear();
    dataFetcher.lowMemory();
  }

  public SearchCompleteListener getSearchCompleteListener() {
    return searchCompleteListener;
  }

  public UncoveringDataModel setSearchCompleteListener(SearchCompleteListener searchCompleteListener) {
    this.searchCompleteListener = searchCompleteListener;
    return this;
  }

  public Query getQuery() {
    return query;
  }

  /**
   * Set the state that is required to support Android life cycles.
   * If items are serializable, they are stored as part of state.
   */
  public void setState(Serializable state) {
    ByteArrayInputStream bin = new ByteArrayInputStream((byte[]) state);
    try {
      ObjectInputStream oin = new ObjectInputStream(bin);
      pageSize = oin.readInt();
      size = oin.readInt();
      query = (Query) oin.readObject();
      loadingPlaceHolder = (ITEM) oin.readObject();
      firstQueryResult = oin.readBoolean();
      data = (Map<Integer, AvailableSegment<ITEM>>) oin.readObject();
      for (AvailableSegment<ITEM> s: data.values()) {
        s.setModel(this);
      }
      oin.close();
    } catch (IOException e) {
      Log.e(TAG, "Failed to read the state", e);
    } catch (ClassNotFoundException e) {
      Log.e(TAG, "Failed to read the state", e);
    }
  }

  /**
   * Get the state that is required to support Android life cycles.
   * If items are serializable, they are stored as part of state.
   */
  public Serializable getState() {
    boolean elementsSerializable;

    try {
      elementsSerializable =
         !data.isEmpty() &&
            data.values().iterator().next() instanceof Serializable;
    } catch (Exception e) {
      Log.e(TAG, "Failed to say if elements are serializable");
      elementsSerializable = false;
    }

    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    try {
      ObjectOutputStream oout = new ObjectOutputStream(bout);
      oout.writeInt(pageSize);
      oout.writeInt(size);
      oout.writeObject(query);
      oout.writeObject(loadingPlaceHolder);
      oout.writeBoolean(firstQueryResult);
      if (elementsSerializable) {
        oout.writeObject(data);
      } else {
        oout.writeObject(new ConcurrentHashMap<Integer, ITEM>());
      }
      oout.close();
    } catch (IOException e) {
      Log.e(TAG, "Failed to write the state", e);
    }
    return bout.toByteArray();
  }
}
