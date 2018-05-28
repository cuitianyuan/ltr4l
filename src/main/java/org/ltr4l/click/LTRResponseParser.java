/*
 * Copyright 2018 org.LTR4L
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ltr4l.click;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

public class LTRResponseParser {
  protected final LTRResponse response;

  protected LTRResponseParser(Reader reader) throws IOException{
    ObjectMapper mapper = new ObjectMapper();
    response = mapper.readValue(reader, LTRResponse.class);
  }

  public Map<String, LTRResponse.Doc[]> getQueryMap(){
    Map<String, LTRResponse.Doc[]> qMap = new HashMap<>();
    LTRResponse.LQuery[] queries = response.results.result.data.queries;
    for(LTRResponse.LQuery lQuery : queries)
      qMap.put(lQuery.query, lQuery.docs);
    return qMap;
  }

  public LTRResponse getResponse() {
    return response;
  }
}
