package com.kinetix.notification.domain.ports

import com.kinetix.notification.domain.DeviceNonce

trait NonceSource[F[_]]:
  def next: F[DeviceNonce]
