package com.xl.channelreinforce

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.TextView
import com.meituan.android.walle.WalleChannelReader

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        var channel = "无渠道号"
        val channelInfo = WalleChannelReader.getChannelInfo(this.applicationContext)
        if (channelInfo != null) {
            channel = channelInfo.channel
        }
        findViewById<TextView>(R.id.tv_channel).text = channel
    }
}