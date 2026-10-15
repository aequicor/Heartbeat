package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewLimits
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentResourcePolicy

/** Fixed code in a fresh isolated world: authored scripts cannot replace its JS builtins or read its diagnostics. */
internal fun documentPreviewScript(policy: BrowserDocumentResourcePolicy): String = DOCUMENT_PREVIEW_SCRIPT.replace(
    "EXTERNAL_ASSETS",
    (policy == BrowserDocumentResourcePolicy.ExternalAssets).toString(),
)

private val DOCUMENT_PREVIEW_SCRIPT: String = """
    (async()=>{
      const externalAssets=EXTERNAL_ASSETS;
      const external=url=>externalAssets&&/^https?:\/\//i.test(url);
      await document.fonts.ready;
      await new Promise(resolve=>setTimeout(resolve,50));
      const cap=${BrowserDocumentPreviewLimits.ISSUES}, sampleCap=${BrowserDocumentPreviewLimits.SAMPLES};
      const bounded=v=>String(v).slice(0,${BrowserDocumentPreviewLimits.TEXT_CHARS});
      const number=v=>Number.isFinite(v)?Math.round(Math.max(-1000000,Math.min(1000000,v))*100)/100:0;
      const walker=document.createTreeWalker(document.documentElement,NodeFilter.SHOW_ELEMENT);
      const nodes=[document.documentElement],indices=new WeakMap();
      let next;
      while(nodes.length<2000&&(next=walker.nextNode()))nodes.push(next);
      const issues=[],samples=[];
      let truncated=Boolean(walker.nextNode());
      nodes.forEach((el,index)=>indices.set(el,index));
      const id=el=>bounded(el.tagName.toLowerCase()+(el.id?'#'+bounded(el.id):'@'+indices.get(el)));
      const hasText=el=>{
        let child=el.firstChild,count=0;
        while(child&&count++<128){if(child.nodeType===3&&child.textContent.trim())return true;child=child.nextSibling;}
        return false;
      };
      const add=(kind,el)=>{if(issues.length<cap)issues.push({kind,element:typeof el==='string'?el:id(el)});
        else truncated=true;};
      const visible=(el,style,rect)=>style.display!=='none'&&style.visibility!=='hidden'&&
        Number(style.opacity)!==0&&rect.width>0&&rect.height>0&&rect.bottom>0&&rect.top<innerHeight;
      const named=el=>Boolean(el.getAttribute('aria-label')?.trim()||el.getAttribute('title')?.trim()||
        el.getAttribute('aria-labelledby')?.split(/\s+/).some(key=>document.getElementById(key)?.textContent.trim()));
      const inputName=el=>named(el)||Array.from(el.labels||[]).some(label=>label.textContent.trim());
      const root=document.documentElement,body=document.body;
      const width=Math.max(root.scrollWidth,body?.scrollWidth||0),height=Math.max(root.scrollHeight,body?.scrollHeight||0);
      if(width>innerWidth+1)add('HorizontalOverflow','document');
      const caption=parseFloat(getComputedStyle(root).getPropertyValue('--hb-caption-size'))||11;
      for(const el of nodes){
        const tag=el.tagName.toLowerCase(),style=getComputedStyle(el),rect=el.getBoundingClientRect();
        const shown=visible(el,style,rect);
        if(shown){
          if(rect.right>innerWidth+1||rect.left< -1)add('HorizontalOverflow',el);
          if(parseFloat(style.fontSize)<caption&&hasText(el))
            add('SmallText',el);
          if((tag==='input'&&!['hidden','button','submit','reset','image'].includes(el.type)||
              tag==='select'||tag==='textarea')&&!inputName(el))add('UnlabelledInput',el);
          if((tag==='button'||el.getAttribute('role')==='button')&&!named(el)&&!el.textContent.trim()&&
              !el.querySelector('img[alt]:not([alt=""])'))add('UnnamedButton',el);
          if(samples.length<sampleCap&&(el===body||el.matches('h1,h2,h3,h4,h5,h6,input,select,textarea,button,nav,main')))
            samples.push({element:id(el),tag,x:number(rect.x),y:number(rect.y),width:number(rect.width),
              height:number(rect.height),fontFamily:bounded(style.fontFamily),fontSize:number(parseFloat(style.fontSize)),
              lineHeight:bounded(style.lineHeight),color:bounded(style.color),backgroundColor:bounded(style.backgroundColor)});
        }
        const sheet=tag==='style'?el.textContent:'',inline=el.getAttribute('style')||'';
        if(sheet.length>8192||inline.length>4096)truncated=true;
        const css=sheet.slice(0,8192)+';'+inline.slice(0,4096);
        let remoteCss=!externalAssets&&/@import\b/i.test(css);
        for(const match of css.matchAll(/url\(\s*(['"]?)(.*?)\1\s*\)/gi)){
          if(!/^(data:|blob:|#)/i.test(match[2].trim())&&!external(match[2].trim())){remoteCss=true;break;}
        }
        if(remoteCss)add('ExternalResource',el);
        const src=el.getAttribute('src'),href=el.getAttribute('href');
        if((tag==='script'&&src&&!external(src))||
          (tag==='link'&&href&&el.rel==='stylesheet'&&!external(href))||
          (['img','video','audio','source'].includes(tag)&&src&&!/^(data:|blob:)/i.test(src)&&!external(src))||
          (tag==='iframe'&&src))
          add('ExternalResource',el);
      }
      return {metrics:{viewportWidth:innerWidth,viewportHeight:innerHeight,contentWidth:number(width),
        contentHeight:number(height),horizontalOverflow:width>innerWidth+1},issues,issuesTruncated:truncated,samples};
    })()
""".trimIndent()
