package adaptorlib;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.zip.*;

/**
 * Tests for {@link AutoUnzipAdaptor}.
 */
public class AutoUnzipAdaptorTest {
  private List<DocId> getDocIds(Adaptor adaptor) throws IOException,
      InterruptedException {
    AccumulatingDocIdPusher pusher = new AccumulatingDocIdPusher();
    adaptor.setDocIdPusher(pusher);
    adaptor.getDocIds(pusher);
    return pusher.getDocIds();
  }

  private byte[] getDocContent(Adaptor adaptor, DocId docId) throws IOException,
      InterruptedException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    adaptor.getDocContent(new WrapperAdaptor.GetContentsRequest(docId),
                          new WrapperAdaptor.GetContentsResponse(baos));
    return baos.toByteArray();
  }

  @Test
  public void testEscape() throws Exception {
    final Charset charset = Charset.forName("US-ASCII");
    final List<DocId> original = Arrays.asList(new DocId[] {
          new DocId("test1"),
          new DocId("test2!"),
          new DocId("!test3"),
          new DocId("!test4!"),
          new DocId("test!test5!test"),
          new DocId("test6!!test"),
        });
    AutoUnzipAdaptor adaptor = new AutoUnzipAdaptor(new AbstractAdaptor() {
      @Override
      public void getDocIds(DocIdPusher pusher) throws InterruptedException {
        pusher.pushDocIds(original);
      }

      @Override
      public void getDocContent(Request req, Response resp) throws IOException {
        String uniqueId = req.getDocId().getUniqueId();
        resp.getOutputStream().write(uniqueId.getBytes(charset));
      }
    });

    // Check encoding
    List<DocId> expected = Arrays.asList(new DocId[] {
        new DocId("test1"),
        new DocId("test2\\!"),
        new DocId("\\!test3"),
        new DocId("\\!test4\\!"),
        new DocId("test\\!test5\\!test"),
        new DocId("test6\\!\\!test"),
      });
    List<DocId> retrieved = getDocIds(adaptor);
    assertEquals("encoding", expected, retrieved);

    // Check decoding
    for (int i = 0; i < original.size(); i++) {
      assertArrayEquals("decoding",
                        original.get(i).getUniqueId().getBytes(charset),
                        getDocContent(adaptor, retrieved.get(i)));
    }
  }

  @Test
  public void testUnzipException() throws Exception {
    AutoUnzipAdaptor adaptor = new AutoUnzipAdaptor(new AbstractAdaptor() {
      @Override
      public void getDocIds(DocIdPusher pusher) throws InterruptedException {
        pusher.pushDocIds(Arrays.asList(new DocId[] {new DocId("test.zip")}));
      }

      @Override
      public void getDocContent(Request req, Response resp) throws IOException {
        throw new IOException();
      }
    });
    List<DocId> expected = Arrays.asList(new DocId[] {
        new DocId("test.zip"),
      });
    assertEquals(expected, getDocIds(adaptor));
  }

  @Test
  public void testUnzip() throws Exception {
    final Charset charset = Charset.forName("US-ASCII");
    final List<String> original = Arrays.asList(new String[] {
          "test1",
          "test2!",
          "!test3",
          "!test4!",
          "test!test5!test",
          "test6!!test",
        });
    AutoUnzipAdaptor adaptor = new AutoUnzipAdaptor(new AbstractAdaptor() {
      @Override
      public void getDocIds(DocIdPusher pusher) throws InterruptedException {
        pusher.pushDocIds(Arrays.asList(new DocId[] {new DocId("!test.zip")}));
      }

      @Override
      public void getDocContent(Request req, Response resp) throws IOException {
        DocId docId = req.getDocId();
        if (!"!test.zip".equals(docId.getUniqueId())) {
          throw new FileNotFoundException(docId.getUniqueId());
        }
        ZipOutputStream zos = new ZipOutputStream(resp.getOutputStream());
        for (String entry : original) {
          zos.putNextEntry(new ZipEntry(entry));
          zos.write(entry.getBytes(charset));
        }
        zos.close();
      }
    });

    // Check encoding
    List<DocId> expected = Arrays.asList(new DocId[] {
        new DocId("\\!test.zip"),
        new DocId("\\!test.zip!test1"),
        new DocId("\\!test.zip!test2\\!"),
        new DocId("\\!test.zip!\\!test3"),
        new DocId("\\!test.zip!\\!test4\\!"),
        new DocId("\\!test.zip!test\\!test5\\!test"),
        new DocId("\\!test.zip!test6\\!\\!test"),
      });
    List<DocId> retrieved = getDocIds(adaptor);
    assertEquals(expected, retrieved);

    // Check decoding
    for (int i = 0; i < original.size(); i++) {
      assertArrayEquals(original.get(i).getBytes(charset),
                        getDocContent(adaptor, retrieved.get(i + 1)));
    }
  }

  private static class AccumulatingDocIdPusher implements Adaptor.DocIdPusher {
    private List<DocId> ids = new ArrayList<DocId>();

    public DocId pushDocIds(Iterable<DocId> docIds)
        throws InterruptedException {
      return pushDocIds(docIds, null);
    }

    public DocId pushDocIds(Iterable<DocId> docIds,
                            Adaptor.PushErrorHandler handler)
        throws InterruptedException {
      for (DocId id : docIds) {
        ids.add(id);
      }
      return null;
    }

    public List<DocId> getDocIds() {
      return Collections.unmodifiableList(ids);
    }

    public void reset() {
      ids.clear();
    }
  }
}