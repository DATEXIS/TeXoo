package de.datexis.preprocess;

import com.google.common.base.Optional;
import com.optimaize.langdetect.LanguageDetector;
import com.optimaize.langdetect.LanguageDetectorBuilder;
import com.optimaize.langdetect.i18n.LdLocale;
import com.optimaize.langdetect.ngram.NgramExtractors;
import com.optimaize.langdetect.profiles.LanguageProfile;
import com.optimaize.langdetect.profiles.LanguageProfileReader;
import com.optimaize.langdetect.text.CommonTextObjectFactories;
import com.optimaize.langdetect.text.TextObject;
import com.optimaize.langdetect.text.TextObjectFactory;
import de.datexis.common.Resource;
import de.datexis.common.WordHelpers;
import java.util.ArrayList;
import java.util.List;
import static de.datexis.common.WordHelpers.skipSpaceAfter;
import static de.datexis.common.WordHelpers.skipSpaceBefore;
import de.datexis.model.Document;
import de.datexis.model.Sentence;
import de.datexis.model.Token;
import java.io.IOException;
import java.util.LinkedList;
import java.util.TreeMap;
import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates a fully tokenized Document from raw text or Stanford Tokens.
 * @author sarnold, fgrimme
 */
public class DocumentFactory {

  protected final static Logger log = LoggerFactory.getLogger(DocumentFactory.class);

  protected static DocumentFactory instance = new DocumentFactory();
  
  public static DocumentFactory getInstance() {
    return instance;
  }
  
  public static enum Newlines { 
    KEEP, // keep all newlines in the text and use them as sentence breaks
    KEEP_DOUBLE, // keep only double newlines in the text, but use all of them as sentence breaks
    DISCARD // discard all newlines in the text but still use them as sentence breaks
  };
  
  private final static String LANG_EN = "en";
  private final static String LANG_DE = "de";
  
  TreeMap<String, SentenceDetectorME> sentenceSplitter;
  TreeMap<String, TokenizerME> plainTokenizer;
  TreeMap<String, TokenizerMENL> newlineTokenizer;
  
  TextObjectFactory textObjectFactory;
  LanguageDetector languageDetector;
  
  
  /**
   * Create a new DocumentFactory instance. Use this only if you need multiple instances!
   * Otherwise, getInstance() will return a singleton object that you can use.
   */
  public DocumentFactory() {
    
    sentenceSplitter = new TreeMap<>();
    plainTokenizer = new TreeMap<>();
    newlineTokenizer = new TreeMap<>();
    
    try {
      SentenceModel sentenceModel = new SentenceModel(Resource.fromJAR("openNLP/en-sent.bin").getInputStream());
      TokenizerModel tokenModel = new TokenizerModel(Resource.fromJAR("openNLP/en-token.bin").getInputStream());
      sentenceSplitter.put(LANG_EN, new SentenceDetectorME(sentenceModel));
      plainTokenizer.put(LANG_EN, new TokenizerME(tokenModel));
      newlineTokenizer.put(LANG_EN, new TokenizerMENL(tokenModel));
      sentenceModel = new SentenceModel(Resource.fromJAR("openNLP/de-sent.bin").getInputStream());
      tokenModel = new TokenizerModel(Resource.fromJAR("openNLP/de-token.bin").getInputStream());
      sentenceSplitter.put(LANG_DE, new SentenceDetectorME(sentenceModel));
      plainTokenizer.put(LANG_DE, new TokenizerME(tokenModel));
      newlineTokenizer.put(LANG_DE, new TokenizerMENL(tokenModel));
    } catch (IOException ex) {
      log.error("CRITICAL! cannot load openNLP models {}", ex.toString());
    }
    
    try {
      //load all languages:
      List<LanguageProfile> languageProfiles = new LanguageProfileReader().readAllBuiltIn();
      //build language detector:
      languageDetector = LanguageDetectorBuilder.create(NgramExtractors.standard())
              .withProfiles(languageProfiles)
              .build();
      //create a text object factory
      textObjectFactory = CommonTextObjectFactories.forDetectingOnLargeText();
    } catch (IOException ex) {
      log.error("Could not load language profiles");
    }
  }
  
  /**
   * Creates a Document with Sentences and Tokens from a String.
   * Uses Stanford CoreNLP PTBTokenizerFactory and removes Tabs, Newlines and trailing whitespace.
   * Use fromText(text, true) to keep the original String in memory.
   * @param text
   * @return 
   */
  public static Document fromText(String text) {
		return instance.createFromText(text);
	}
  
  public static Document fromText(String text, Newlines newlines) {
		return instance.createFromText(text, newlines);
	}
  
  /**
   * Creates a Document from existing Tokens, processing Span positions and Sentence splitting.
   */
  public static Document fromTokens(List<Token> tokens) {
		return instance.createFromTokens(tokens);
	}
  
  /**
   * Create Tokens from raw text, without sentence splitting.
   */
  public static List<Token> createTokensFromText(String text) {
		return instance.tokenizeFast(text);
	}
  
  /**
   * Create Tokens from tokenized text, without sentence splitting.
   */
  public static List<Token> createTokensFromTokenizedText(String text) {
    return instance.createTokensFromTokenizedText(text, 0);
  }
    
  /**
   * Creates a Document with Sentences and Tokens from a String.
   * Uses Stanford CoreNLP PTBTokenizerFactory.
   * @param text
   * @param keepOrig - if TRUE, a copy of the string is saved to keep newlines and tabs.
   * @return 
   */
  public Document createFromText(String text) {
    Document doc = new Document();
    addToDocumentFromText(text, doc, Newlines.DISCARD);
    return doc;
  }
  
  public Document createFromText(String text, Newlines newlines) {
    Document doc = new Document();
    addToDocumentFromText(text, doc, newlines);
    return doc;
  }
  
  public void addToDocumentFromText(String text, Document doc, Newlines newlines) {
    String lang = doc.getLanguage();
    if(lang == null) {
      lang = detectLanguage(text);
      //lang = WordHelpers.getLanguage(language);
      if(!lang.isEmpty()) doc.setLanguage(lang);
    }
    
    int docOffset = doc.getEnd();
    if(docOffset > 0) docOffset++;
    
    // find best Tokenizer and Splitter for text
    TokenizerME tokenizer = newlineTokenizer.getOrDefault(lang, newlineTokenizer.get(LANG_EN));
    SentenceDetectorME ssplit = sentenceSplitter.getOrDefault(lang, sentenceSplitter.get(LANG_EN));
    
    opennlp.tools.util.Span sentences[] = ssplit.sentPosDetect(text); 
    
    // go over sentences and split again at any newline characters
    LinkedList<opennlp.tools.util.Span> splitSentences = new LinkedList<>();
    int cursor = 0;
    for(opennlp.tools.util.Span span : sentences) {

      // check for newlines in between sentences, they belong to previous sentence
      String sentenceText = text.substring(cursor, span.getStart());
      if(!splitSentences.isEmpty() && sentenceText.contains("\n")) {
        opennlp.tools.util.Span prev = splitSentences.pollLast();
        prev = new opennlp.tools.util.Span(prev.getStart(), prev.getEnd() + sentenceText.length());
        splitSentences.add(prev);
        cursor = prev.getEnd();
      }

      // check for newlines in sentence
      sentenceText = text.substring(span.getStart(), span.getEnd());
      while(sentenceText.contains("\n")) {
        int offset = sentenceText.indexOf("\n");
        if(offset == 0 && !splitSentences.isEmpty()) {
          // newline at beginning belongs to previous sentence
          opennlp.tools.util.Span prev = splitSentences.pollLast();
          prev = new opennlp.tools.util.Span(prev.getStart(), prev.getEnd() + 1);
          splitSentences.add(prev);
          offset++;
        } else {
          // newline in between requires split
          opennlp.tools.util.Span split = new opennlp.tools.util.Span(span.getStart(), span.getStart() + offset);
          splitSentences.add(split);
        }
        span = new opennlp.tools.util.Span(span.getStart() + offset, span.getEnd());
        sentenceText = text.substring(span.getStart(), span.getEnd());
        cursor = span.getEnd();
      }

      // add remaining sentence
      if(span.length() > 0) splitSentences.add(span);
      cursor = span.getEnd();
    }

    // Tokenize sentences
    int countNewlines = 0;
    int nlOffset = 0; // number of skipped newlines
    for(opennlp.tools.util.Span span : splitSentences) {
      String sentenceText = text.substring(span.getStart(), span.getEnd());
      opennlp.tools.util.Span tokens[] = tokenizer.tokenizePos(sentenceText);
      List<Token> tokenList = new LinkedList<>();
      for(opennlp.tools.util.Span token : tokens) {
        String tokenText = sentenceText.substring(token.getStart(), token.getEnd());
        if(tokenText.equals("\n")) { // newline
          countNewlines++;
          if(newlines == Newlines.KEEP) { // newline is a paragraph
            tokenList.add(new Token(tokenText, docOffset - nlOffset + span.getStart() + token.getStart(), docOffset - nlOffset + span.getStart() + token.getEnd()));
          } else if(newlines == Newlines.KEEP_DOUBLE && countNewlines == 2) { // two newlines are a new paragraph, skip next though
            tokenList.add(new Token(tokenText, docOffset - nlOffset + span.getStart() + token.getStart(), docOffset - nlOffset + span.getStart() + token.getEnd()));
          } else if(newlines == Newlines.DISCARD) { // skip newlines, but keep one whitespace
            if(countNewlines > 1) nlOffset++;
          } else {
            nlOffset++;
          }
        } else {
          tokenList.add(new Token(tokenText, docOffset - nlOffset + span.getStart() + token.getStart(), docOffset - nlOffset + span.getStart() + token.getEnd()));
          countNewlines = 0;
        }
      }
      Sentence sentence = new Sentence(tokenList);
      doc.addSentence(sentence, false);
    }
  }
  
  // FIXME: do we still need this function after CoreNLP replacement?
  public List<Token> tokenizeFast(String text) {
    return createTokensFromText(text, 0);
  }
  
  public static String getLanguage(String text) {
    return instance.detectLanguage(text);
  }
  
  public synchronized String detectLanguage(String text) {
    try {
      TextObject textObject = textObjectFactory.forText(text);
      Optional<LdLocale> locale = languageDetector.detect(textObject);
      if(locale.isPresent()) return locale.get().getLanguage();
    } catch(Exception e) {}
    return "";
  }
  
public Document createFromTokens(List<Token> tokens) {
    Document doc = new Document();
    createSentencesFromTokens(tokens).forEach(sentence -> {
      doc.addSentence(sentence, false);
    });
    doc.setLanguage(detectLanguage(doc.getText()));
    return doc;
  }

  public static Sentence createSentenceFromTokens(List<Token> sentence) {
    return instance.createSentenceFromTokens(sentence, "", 0);
  }
  
  public List<Sentence> createSentencesFromTokens(List<Token> tokens) {
    List<Sentence> sentences = new LinkedList<>();
    int lastCursorPos = 0;
    String lastTokenString = "";
    // FIXME: implement for OpenNLP
    //for(List<Token> sentenceTokens : instance.tts.process(tokens)) {
    List<Token> sentenceTokens = tokens;
      Sentence sentence = createSentenceFromTokens(sentenceTokens, lastTokenString, lastCursorPos);
      lastCursorPos = sentence.getEnd();
      int lastTokenIndex = sentence.getTokens().size() - 1;
      lastTokenString = sentence.getToken(lastTokenIndex).getText();
      sentences.add(sentence);
    //}
    return sentences;
  }
  
  private Sentence createSentenceFromTokens(List<Token> sentence, String last, Integer cursor) {
    int length;
    Sentence s = new Sentence();
    s.setBegin(cursor);
    for(Token t : sentence) {
      if(!skipSpaceAfter.contains(last) && !skipSpaceBefore.contains(t.getText())) cursor++;
      length = t.getText().length();
      t.setBegin(cursor);
      t.setLength(length);
      cursor += length;
      last = t.getText();
      s.addToken(t);
    }
    s.setEnd(cursor);
    return s;
  }

  /**
   * Creates a list of Tokens from raw text (ignores sentences)
   */
  public List<Token> createTokensFromText(String text, int offset) {
    String language = detectLanguage(text);
    TokenizerME tokenizer = plainTokenizer.getOrDefault(language, plainTokenizer.get(LANG_EN));
    opennlp.tools.util.Span tokens[] = tokenizer.tokenizePos(text);
    List<Token> tokenList = new LinkedList<>();
    for(opennlp.tools.util.Span token : tokens) {
      String tokenText = text.substring(token.getStart(), token.getEnd());
      Token t = new Token(tokenText, offset + token.getStart(), offset + token.getEnd());
      tokenList.add(t);
    }
    return tokenList;
  }
  
  /**
   * Creates a list of Tokens from tokenized text, keeping the original tokenization.
   */
  public List<Token> createTokensFromTokenizedText(String text, int offset) {
    List<Token> tokens = new ArrayList<>();
    String last = "";
    for(String token : WordHelpers.splitSpaces(text)) {
      int length = token.length();
      Token t = new Token(token, offset, offset + length);
      offset += length;
      if(!skipSpaceAfter.contains(last) && !skipSpaceBefore.contains(token)) offset++;
      tokens.add(t);
      last = token;
    }
    return tokens;
  }
  
  /**
   * Recreates the document with automatic tokenization. Offsets are kept.
   */
  public void retokenize(Document doc) {
    doc.setText(doc.getText());
  }
  
}
