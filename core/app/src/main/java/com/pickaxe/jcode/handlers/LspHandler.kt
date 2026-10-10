package com.pickaxe.jcode.handlers

// import com.pickaxe.jcode.lsp.clang.ClangLanguageServer || planned for v..03
import android.content.Context
import com.pickaxe.jcode.lsp.api.ILanguageClient
import com.pickaxe.jcode.lsp.api.ILanguageServerRegistry
import com.pickaxe.jcode.lsp.java.JavaLanguageServer
import com.pickaxe.jcode.lsp.clang.ClangLanguageServer
import com.pickaxe.jcode.lsp.kotlin.KotlinLanguageServer
import com.pickaxe.jcode.lsp.xml.XMLLanguageServer

/** @author Akash Yadav */
object LspHandler {

  fun registerLanguageServers(context: Context) {
    ILanguageServerRegistry.getDefault().apply {
      getServer(JavaLanguageServer.SERVER_ID) ?: register(JavaLanguageServer())
      getServer(KotlinLanguageServer.SERVER_ID) ?: register(KotlinLanguageServer(context))
      getServer(ClangLanguageServer.SERVER_ID) ?: register(ClangLanguageServer(context))
      getServer(XMLLanguageServer.SERVER_ID) ?: register(XMLLanguageServer())
    }
  }

  fun connectClient(client: ILanguageClient) {
    ILanguageServerRegistry.getDefault().connectClient(client)
  }

  fun destroyLanguageServers(isConfigurationChange: Boolean) {
    if (isConfigurationChange) {
      return
    }
    ILanguageServerRegistry.getDefault().destroy()
  }
}
